#!/usr/bin/env python3
"""
reallyFAST! - sestaveni slovniku (app/src/main/assets/dictionary.bin).

Zdroje (zadna vymyslena slova):
  A) Hunspell cs_CZ (LibreOffice dictionaries, GPL-2.0)  -> spravnost, vsechny tvary
     dictionaries/cs_CZ.dic + cs_CZ.aff (kdyz chybi, stahnou se z GitHubu LibreOffice)
  B) Cesky frekvencni seznam lemmat (CNK, CC BY 4.0)      -> poradi castosti
     dictionaries/czech_freq_15k.txt (jedno lemma na radek, serazeno od nejcastejsiho)
  C) Leipzig Corpora Collection (CC BY 4.0)               -> frekvence tvaru + bigramy + trigramy
     eng_news_2020_300K (anglictina), ces_news_2020_300K (cestina)

Postup: expanze Hunspell tvaru -> cisteni -> lowercase -> deduplikace -> frekvence
-> TSV v dictionaries/build/ -> Rust nastroj `build-dict` z nich postavi FST (komprimovany trie).

Pouziti:
    python scripts/download-dictionary.py            # vse
    python scripts/download-dictionary.py --no-build # jen TSV, bez cargo
"""
import argparse
import math
import re
import subprocess
import sys
import tarfile
import urllib.request
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "dictionaries"
CACHE = SRC / "cache"
BUILD = SRC / "build"
OUT = ROOT / "app" / "src" / "main" / "assets" / "dictionary.bin"

HUNSPELL_URL = "https://raw.githubusercontent.com/LibreOffice/dictionaries/master/cs_CZ/"
LEIPZIG_URL = "https://downloads.wortschatz-leipzig.de/corpora/{}.tar.gz"
CS_CORPUS = "ces_news_2020_300K"
EN_CORPUS = "eng_news_2020_300K"

# Musi odpovidat abecede v crates/keyboard-core/src/charmap.rs
ALPHABET = "abcdefghijklmnopqrstuvwxyzáčďéěíňóřšťúůýžäöüôľĺŕëñ"
CS_WORD = re.compile("^[" + ALPHABET + "]{1,30}$")
EN_WORD = re.compile("^[a-z]+(?:'[a-z]+)?$")

DEFAULT_ZIPF = 1.0          # slova bez frekvence
EN_MAX_WORDS = 110_000
EN_MIN_COUNT = 2
BIGRAMS_PER_WORD = 6


def log(msg):
    print(msg, flush=True)


def download(url, dest):
    if dest.exists() and dest.stat().st_size > 0:
        return
    dest.parent.mkdir(parents=True, exist_ok=True)
    log(f"  stahuji {url}")
    tmp = dest.with_suffix(dest.suffix + ".part")
    req = urllib.request.Request(url, headers={"User-Agent": "dictionary-build-script"})
    with urllib.request.urlopen(req, timeout=120) as r, open(tmp, "wb") as f:
        while True:
            chunk = r.read(1 << 20)
            if not chunk:
                break
            f.write(chunk)
    tmp.replace(dest)


def zipf_to_u8(z):
    return max(1, min(255, int(round(z * 32))))


# --------------------------------------------------------------------------- Hunspell

class Rule:
    __slots__ = ("strip", "add", "cond", "cont", "cross")

    def __init__(self, strip, add, cond, cont, cross):
        self.strip, self.add, self.cond, self.cont, self.cross = strip, add, cond, cont, cross


def parse_aff(path):
    sfx, pfx = defaultdict(list), defaultdict(list)
    cross = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        p = line.split()
        if len(p) < 4 or p[0] not in ("SFX", "PFX"):
            continue
        kind, flag = p[0], p[1]
        if len(p) == 4 and p[2] in ("Y", "N") and p[3].isdigit():
            cross[(kind, flag)] = p[2] == "Y"
            continue
        strip = "" if p[2] == "0" else p[2]
        add, _, cont = p[3].partition("/")
        add = "" if add == "0" else add
        cond = p[4] if len(p) > 4 else "."
        try:
            if cond == ".":
                rx = None
            elif kind == "SFX":
                rx = re.compile("(?:" + cond + ")$")
            else:
                rx = re.compile("^(?:" + cond + ")")
        except re.error:
            continue
        rule = Rule(strip, add, rx, cont, cross.get((kind, flag), True))
        (sfx if kind == "SFX" else pfx)[flag].append(rule)
    return sfx, pfx


def apply_sfx(word, rules):
    for r in rules:
        if r.strip and not word.endswith(r.strip):
            continue
        if r.cond is not None and not r.cond.search(word):
            continue
        base = word[: len(word) - len(r.strip)] if r.strip else word
        yield base + r.add, r


def apply_pfx(word, rules):
    for r in rules:
        if r.strip and not word.startswith(r.strip):
            continue
        if r.cond is not None and not r.cond.search(word):
            continue
        yield r.add + word[len(r.strip):], r


def expand(stem, flags, sfx, pfx):
    """Vrati (tvary bez predpony, tvary s predponou)."""
    plain = {stem}
    crossable = [stem]
    prefixed = set()
    for fl in flags:
        rules = sfx.get(fl)
        if not rules:
            continue
        for form, r in apply_sfx(stem, rules):
            plain.add(form)
            if r.cross:
                crossable.append(form)
            for f2 in r.cont:  # dvouurovnove pripony
                if f2 in sfx:
                    for form2, r2 in apply_sfx(form, sfx[f2]):
                        plain.add(form2)
                        if r2.cross:
                            crossable.append(form2)
                if f2 in pfx:
                    for form2, _ in apply_pfx(form, pfx[f2]):
                        prefixed.add(form2)
    for fl in flags:
        rules = pfx.get(fl)
        if not rules:
            continue
        for base in crossable:
            for form, _ in apply_pfx(base, rules):
                prefixed.add(form)
    return plain, prefixed - plain


# --------------------------------------------------------------------------- Leipzig

def read_leipzig(name):
    """Vrati (pocty presnych tvaru, id->slovo, cesta k archivu)."""
    archive = CACHE / name / f"{name}.tar.gz"
    download(LEIPZIG_URL.format(name), archive)
    counts, ids = {}, {}
    with tarfile.open(archive, "r:gz") as tar:
        member = next(m for m in tar.getmembers() if m.name.endswith("-words.txt"))
        for raw in tar.extractfile(member):
            p = raw.decode("utf-8", "replace").rstrip("\n").split("\t")
            if len(p) < 3:
                continue
            try:
                c = int(p[-1])
            except ValueError:
                continue
            ids[p[0]] = p[1]
            counts[p[1]] = counts.get(p[1], 0) + c
    return counts, ids, archive


def read_bigrams(archive, ids, vocab):
    pairs = defaultdict(int)
    with tarfile.open(archive, "r:gz") as tar:
        member = next(m for m in tar.getmembers() if m.name.endswith("-co_n.txt"))
        for raw in tar.extractfile(member):
            p = raw.decode("utf-8", "replace").split("\t")
            if len(p) < 3:
                continue
            a, b = ids.get(p[0]), ids.get(p[1])
            if a is None or b is None:
                continue
            a, b = a.lower(), b.lower()
            if a in vocab and b in vocab:
                try:
                    pairs[(a, b)] += int(p[2])
                except ValueError:
                    pass
    by_first = defaultdict(list)
    for (a, b), c in pairs.items():
        if c >= 2:
            by_first[a].append((c, b))
    out = []
    for a, lst in by_first.items():
        lst.sort(reverse=True)
        for c, b in lst[:BIGRAMS_PER_WORD]:
            out.append((a, b, c))
    out.sort()
    return out


TOKEN = re.compile(r"[^\W\d_]+(?:'[^\W\d_]+)?")
CLAUSE_SPLIT = re.compile(r"[.!?;:,()\"„“”–—]+")
TRIGRAMS_PER_CONTEXT = 5
TRIGRAM_MIN_COUNT = 3


def read_trigrams(archive, vocab):
    """Trigramy (w1, w2 -> w3) z vet korpusu; jen slova ze slovniku, v ramci jedne vetne casti."""
    counts = defaultdict(int)
    with tarfile.open(archive, "r:gz") as tar:
        member = next(m for m in tar.getmembers() if m.name.endswith("-sentences.txt"))
        for raw in tar.extractfile(member):
            line = raw.decode("utf-8", "replace")
            text = line.split("	", 1)[-1].lower()
            for clause in CLAUSE_SPLIT.split(text):
                toks = TOKEN.findall(clause)
                for i in range(len(toks) - 2):
                    a, b, c = toks[i], toks[i + 1], toks[i + 2]
                    if a in vocab and b in vocab and c in vocab:
                        counts[(a, b, c)] += 1
    by_ctx = defaultdict(list)
    for (a, b, c), n in counts.items():
        if n >= TRIGRAM_MIN_COUNT:
            by_ctx[(a, b)].append((n, c))
    out = []
    for (a, b), lst in by_ctx.items():
        lst.sort(reverse=True)
        for n, c in lst[:TRIGRAMS_PER_CONTEXT]:
            out.append((a, b, c, n))
    out.sort()
    return out


def fold_counts(counts):
    """Agregace podle lowercase: (celkem, pocet malych, pocet s velkym pocatecnim)."""
    agg = {}
    for w, c in counts.items():
        lw = w.lower()
        e = agg.get(lw)
        if e is None:
            e = agg[lw] = [0, 0, 0]
        if w == lw:
            e[1] += c
            e[0] += c
        elif w[:1].isupper() and w[1:] == lw[1:]:
            e[2] += c
            e[0] += c
        elif len(w) > 1 and w.isupper():
            continue  # zkratky psane kapitalkami nepocitame
        else:
            e[0] += c
    return agg


def write_tsv(path, rows):
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        for row in rows:
            f.write("\t".join(str(x) for x in row) + "\n")


# --------------------------------------------------------------------------- cestina

def build_czech():
    for name in ("cs_CZ.dic", "cs_CZ.aff"):
        download(HUNSPELL_URL + name, SRC / name)
    freq_file = SRC / "czech_freq_15k.txt"
    if not freq_file.exists():
        sys.exit(f"Chybi {freq_file} (frekvencni seznam lemmat CNK, jedno slovo na radek).")

    log("[cs] frekvencni seznam lemmat")
    lemma_zipf = {}
    for rank, line in enumerate(freq_file.read_text(encoding="utf-8").split(), start=1):
        w = line.strip().lower()
        if w and w not in lemma_zipf:
            # Zipfuv zakon: f(r) ~ 6e7 / r vyskytu na miliardu slov
            lemma_zipf[w] = math.log10(6e7 / rank)

    log("[cs] Leipzig korpus " + CS_CORPUS)
    counts, ids, archive = read_leipzig(CS_CORPUS)
    agg = fold_counts(counts)
    total = sum(e[0] for e in agg.values()) or 1

    log("[cs] expanze Hunspell tvaru (chvili trva)")
    sfx, pfx = parse_aff(SRC / "cs_CZ.aff")
    words = {}  # slovo -> [zipf, cap]
    n_stems = 0
    lines = (SRC / "cs_CZ.dic").read_text(encoding="utf-8").splitlines()[1:]
    for line in lines:
        entry = line.split()[0] if line.strip() else ""
        if not entry:
            continue
        stem, _, flags = entry.partition("/")
        if not stem or (len(stem) > 1 and stem.isupper()):
            continue
        low = stem.lower()
        if not CS_WORD.match(low):
            continue
        n_stems += 1
        is_cap = stem[0].isupper()
        lz = lemma_zipf.get(low)
        plain, prefixed = expand(stem, flags, sfx, pfx)
        for group, is_prefixed in ((plain, False), (prefixed, True)):
            for form in group:
                f = form.lower()
                if not CS_WORD.match(f):
                    continue
                e = agg.get(f)
                z = None
                if e is not None and e[0] > 0:
                    z = math.log10(e[0] / total * 1e9)
                    if lz is not None and f == low:
                        z = max(z, lz - 0.5)
                elif lz is not None and not is_prefixed:
                    z = (lz - 0.5) if f == low else max(1.5, lz - 2.0)
                elif not is_cap and not is_prefixed:
                    z = DEFAULT_ZIPF
                if z is None:
                    continue
                cur = words.get(f)
                if cur is None:
                    words[f] = [z, is_cap]
                else:
                    if z > cur[0]:
                        cur[0] = z
                    cur[1] = cur[1] and is_cap
    log(f"[cs] {n_stems} hesel -> {len(words)} tvaru")

    rows = [(w, zipf_to_u8(z), 1 if cap else 0) for w, (z, cap) in sorted(words.items())]
    write_tsv(BUILD / "cs_words.tsv", rows)
    log("[cs] bigramy")
    write_tsv(BUILD / "cs_bigrams.tsv", read_bigrams(archive, ids, words))
    log("[cs] trigramy")
    write_tsv(BUILD / "cs_trigrams.tsv", read_trigrams(archive, words))


# --------------------------------------------------------------------------- anglictina

def build_english():
    log("[en] Leipzig korpus " + EN_CORPUS)
    counts, ids, archive = read_leipzig(EN_CORPUS)
    agg = fold_counts(counts)
    total = sum(e[0] for e in agg.values()) or 1
    cand = [(e[0], w, e) for w, e in agg.items()
            if e[0] >= EN_MIN_COUNT and len(w) <= 30 and EN_WORD.match(w)]
    cand.sort(reverse=True)
    cand = cand[:EN_MAX_WORDS]
    rows = []
    for c, w, e in cand:
        cap = 1 if e[2] > 3 * e[1] else 0
        rows.append((w, zipf_to_u8(math.log10(c / total * 1e9)), cap))
    rows.sort()
    log(f"[en] {len(rows)} slov")
    write_tsv(BUILD / "en_words.tsv", rows)
    log("[en] bigramy")
    vocab = {r[0] for r in rows}
    write_tsv(BUILD / "en_bigrams.tsv", read_bigrams(archive, ids, vocab))
    log("[en] trigramy")
    write_tsv(BUILD / "en_trigrams.tsv", read_trigrams(archive, vocab))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--no-build", action="store_true", help="nevolat cargo (jen TSV)")
    args = ap.parse_args()
    build_czech()
    build_english()
    if args.no_build:
        return
    log("[bin] cargo run --bin build-dict")
    OUT.parent.mkdir(parents=True, exist_ok=True)
    subprocess.check_call([
        "cargo", "run", "--release",
        "--manifest-path", str(ROOT / "crates" / "keyboard-core" / "Cargo.toml"),
        "--bin", "build-dict", "--", str(BUILD), str(OUT),
    ])
    log(f"Hotovo: {OUT} ({OUT.stat().st_size / 1e6:.1f} MB)")


if __name__ == "__main__":
    main()
