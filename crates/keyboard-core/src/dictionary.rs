//! Vestaveny slovnik: komprimovany trie (FST) slovo -> frekvence, tabulka bigramu
//! a tabulka nejcastejsich slov pro kratke predpony.
//!
//! Format souboru `dictionary.bin`:
//!   "RBD3" | u32 pocet jazyku | pro kazdy jazyk: [u8;2] kod + 6x u32 delky sekci | data sekci
//! Sekce jazyka: words.fst, bigram_index.fst, bigram_blob, prefix_index.fst, prefix_blob, swipe.fst.
//! swipe.fst: slova bez diakritiky a apostrofu -> 255 - nejvyssi frekvence variant. Diky tomu je
//! soucet vystupu po ceste predponou = 255 - nejvyssi frekvence slova pod touto predponou
//! (FST tlaci spolecne minimum vystupu ke koreni), coz beam search pouziva jako vyhled. Swipe decoder
//! hleda v nem (prst jede pres zakladni pismena) a varianty s diakritikou dohleda ve words.fst.
//! Hodnota ve words.fst: bity 0-7 frekvence (zipf*32), bit 8 = vlastni jmeno (velke pismeno).
//! Blob: seznam = u8 pocet, pak polozky (u8 delka + zakodovane slovo).

use crate::charmap::{self, fold_bytes};
use fst::raw::{Fst, Output};
use fst::{Map, MapBuilder};
use std::collections::{BTreeMap, HashMap};
use std::fs::File;
use std::io::{self, BufReader, BufWriter, Read, Write};
use std::path::Path;

const MAGIC: &[u8; 4] = b"RBD3";
const CAP_BIT: u64 = 1 << 8;
/// Delka predpon v tabulce nejcastejsich slov (0 = nejcastejsi slova jazyka).
pub const PREFIX_TABLE_LEN: usize = 3;
const PREFIX_TOP: usize = 8;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Entry {
    pub freq: u8,
    pub cap: bool,
}

impl Entry {
    #[inline]
    pub fn from_value(v: u64) -> Entry {
        Entry { freq: (v & 0xFF) as u8, cap: v & CAP_BIT != 0 }
    }
    #[inline]
    fn value(self) -> u64 {
        self.freq as u64 | if self.cap { CAP_BIT } else { 0 }
    }
}

/// Surove sekce jednoho jazyka (vystup builderu, vstup pro zapis do souboru).
pub struct DictData {
    pub words: Vec<u8>,
    pub bigram_idx: Vec<u8>,
    pub bigram_blob: Vec<u8>,
    pub prefix_idx: Vec<u8>,
    pub prefix_blob: Vec<u8>,
    pub swipe: Vec<u8>,
}

pub struct Dictionary {
    pub lang: String,
    words: Map<Vec<u8>>,
    bigram_idx: Map<Vec<u8>>,
    bigram_blob: Vec<u8>,
    prefix_idx: Map<Vec<u8>>,
    prefix_blob: Vec<u8>,
    swipe: Map<Vec<u8>>,
}

fn bad(msg: &str) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, msg.to_string())
}

fn fst_err(e: fst::Error) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, e.to_string())
}

fn push_list(blob: &mut Vec<u8>, items: &[Vec<u8>]) -> u64 {
    let off = blob.len() as u64;
    blob.push(items.len().min(255) as u8);
    for it in items.iter().take(255) {
        blob.push(it.len() as u8);
        blob.extend_from_slice(it);
    }
    off
}

fn read_list(blob: &[u8], off: usize) -> Vec<Vec<u8>> {
    let mut out = Vec::new();
    let Some(&n) = blob.get(off) else { return out };
    let mut p = off + 1;
    for _ in 0..n {
        let Some(&len) = blob.get(p) else { break };
        let Some(item) = blob.get(p + 1..p + 1 + len as usize) else { break };
        out.push(item.to_vec());
        p += 1 + len as usize;
    }
    out
}

impl DictData {
    /// Postavi slovnik. `words`: (slovo, frekvence, vlastni jmeno), `bigrams`: (w1, w2, pocet).
    /// Slova mimo abecedu se tise preskoci.
    pub fn build(words: &[(String, u8, bool)], bigrams: &[(String, String, u32)]) -> io::Result<DictData> {
        let mut map: BTreeMap<Vec<u8>, Entry> = BTreeMap::new();
        for (w, freq, cap) in words {
            let Some(enc) = charmap::encode_word(w) else { continue };
            let e = map.entry(enc).or_insert(Entry { freq: 0, cap: true });
            e.freq = e.freq.max(*freq);
            e.cap = e.cap && *cap;
        }

        let mut folded_words: BTreeMap<Vec<u8>, u8> = BTreeMap::new();
        let mut wb = MapBuilder::memory();
        let mut prefixes: HashMap<Vec<u8>, Vec<(u8, &Vec<u8>)>> = HashMap::new();
        for (enc, e) in &map {
            wb.insert(enc, e.value()).map_err(fst_err)?;
            let folded = fold_bytes(enc);
            let key: Vec<u8> = folded.iter().copied().filter(|&b| b != charmap::APOSTROPHE).collect();
            let f = folded_words.entry(key).or_insert(0);
            *f = (*f).max(e.freq);
            for len in 0..=PREFIX_TABLE_LEN.min(folded.len()) {
                let top = prefixes.entry(folded[..len].to_vec()).or_default();
                if top.len() < PREFIX_TOP {
                    top.push((e.freq, enc));
                } else if let Some(min) = top.iter_mut().min_by_key(|t| t.0) {
                    if e.freq > min.0 {
                        *min = (e.freq, enc);
                    }
                }
            }
        }
        let words_fst = wb.into_inner().map_err(fst_err)?;

        let mut prefix_blob = Vec::new();
        let mut pb = MapBuilder::memory();
        let mut keys: Vec<&Vec<u8>> = prefixes.keys().collect();
        keys.sort();
        for k in keys {
            let mut top = prefixes[k].clone();
            top.sort_by(|a, b| b.0.cmp(&a.0).then(a.1.len().cmp(&b.1.len())).then(a.1.cmp(b.1)));
            let items: Vec<Vec<u8>> = top.into_iter().map(|t| t.1.clone()).collect();
            let off = push_list(&mut prefix_blob, &items);
            pb.insert(k, off).map_err(fst_err)?;
        }
        let prefix_idx = pb.into_inner().map_err(fst_err)?;

        let mut by_first: BTreeMap<Vec<u8>, Vec<(u32, Vec<u8>)>> = BTreeMap::new();
        for (a, b, c) in bigrams {
            let (Some(ea), Some(eb)) = (charmap::encode_word(a), charmap::encode_word(b)) else { continue };
            if map.contains_key(&ea) && map.contains_key(&eb) {
                by_first.entry(ea).or_default().push((*c, eb));
            }
        }
        let mut bigram_blob = Vec::new();
        let mut bb = MapBuilder::memory();
        for (k, mut list) in by_first {
            list.sort_by(|a, b| b.0.cmp(&a.0).then(a.1.cmp(&b.1)));
            list.dedup_by(|a, b| a.1 == b.1);
            let items: Vec<Vec<u8>> = list.into_iter().take(8).map(|t| t.1).collect();
            let off = push_list(&mut bigram_blob, &items);
            bb.insert(&k, off).map_err(fst_err)?;
        }
        let bigram_idx = bb.into_inner().map_err(fst_err)?;

        let mut sb = MapBuilder::memory();
        for (k, f) in &folded_words {
            sb.insert(k, 255 - *f as u64).map_err(fst_err)?;
        }
        let swipe = sb.into_inner().map_err(fst_err)?;

        Ok(DictData { words: words_fst, bigram_idx, bigram_blob, prefix_idx, prefix_blob, swipe })
    }

    fn sections(&self) -> [&Vec<u8>; 6] {
        [&self.words, &self.bigram_idx, &self.bigram_blob, &self.prefix_idx, &self.prefix_blob, &self.swipe]
    }
}

/// Zapise slovniky vsech jazyku do jednoho souboru.
pub fn write_file(path: &Path, langs: &[(&str, &DictData)]) -> io::Result<()> {
    let mut w = BufWriter::new(File::create(path)?);
    w.write_all(MAGIC)?;
    w.write_all(&(langs.len() as u32).to_le_bytes())?;
    for (tag, data) in langs {
        let t = tag.as_bytes();
        if t.len() != 2 {
            return Err(bad("kod jazyka musi mit 2 znaky"));
        }
        w.write_all(t)?;
        for s in data.sections() {
            w.write_all(&(s.len() as u32).to_le_bytes())?;
        }
    }
    for (_, data) in langs {
        for s in data.sections() {
            w.write_all(s)?;
        }
    }
    w.flush()
}

fn read_u32(r: &mut impl Read) -> io::Result<u32> {
    let mut b = [0u8; 4];
    r.read_exact(&mut b)?;
    Ok(u32::from_le_bytes(b))
}

/// Nacte vsechny jazyky ze souboru. Vola se na pozadi, mimo UI vlakno.
pub fn load_file(path: &Path) -> io::Result<Vec<Dictionary>> {
    let mut r = BufReader::new(File::open(path)?);
    let mut magic = [0u8; 4];
    r.read_exact(&mut magic)?;
    if &magic != MAGIC {
        return Err(bad("neznamy format slovniku"));
    }
    let n = read_u32(&mut r)? as usize;
    if n > 16 {
        return Err(bad("prilis mnoho jazyku"));
    }
    let mut headers = Vec::with_capacity(n);
    for _ in 0..n {
        let mut tag = [0u8; 2];
        r.read_exact(&mut tag)?;
        let mut lens = [0usize; 6];
        for l in lens.iter_mut() {
            *l = read_u32(&mut r)? as usize;
            if *l > 256 << 20 {
                return Err(bad("poskozena hlavicka slovniku"));
            }
        }
        headers.push((String::from_utf8_lossy(&tag).into_owned(), lens));
    }
    let mut out = Vec::with_capacity(n);
    for (lang, lens) in headers {
        let mut secs: [Vec<u8>; 6] = Default::default();
        for (sec, len) in secs.iter_mut().zip(lens) {
            sec.resize(len, 0);
            r.read_exact(sec)?;
        }
        let [words, bigram_idx, bigram_blob, prefix_idx, prefix_blob, swipe] = secs;
        let data = DictData { words, bigram_idx, bigram_blob, prefix_idx, prefix_blob, swipe };
        out.push(Dictionary::from_data(&lang, data)?);
    }
    Ok(out)
}

impl Dictionary {
    pub fn from_data(lang: &str, d: DictData) -> io::Result<Dictionary> {
        Ok(Dictionary {
            lang: lang.to_string(),
            words: Map::new(d.words).map_err(fst_err)?,
            bigram_idx: Map::new(d.bigram_idx).map_err(fst_err)?,
            bigram_blob: d.bigram_blob,
            prefix_idx: Map::new(d.prefix_idx).map_err(fst_err)?,
            prefix_blob: d.prefix_blob,
            swipe: Map::new(d.swipe).map_err(fst_err)?,
        })
    }

    /// Pomocny konstruktor pro testy a benchmarky: slova s frekvenci, bez bigramu.
    /// Slovo s velkym pocatecnim pismenem se oznaci jako vlastni jmeno.
    pub fn from_words(lang: &str, words: &[(&str, u8)]) -> Dictionary {
        Self::from_words_bigrams(lang, words, &[])
    }

    /// Pomocny konstruktor pro testy a benchmarky (neni na produkcni ceste).
    pub fn from_words_bigrams(lang: &str, words: &[(&str, u8)], bigrams: &[(&str, &str, u32)]) -> Dictionary {
        let w: Vec<(String, u8, bool)> = words
            .iter()
            .map(|(s, f)| (s.to_lowercase(), *f, s.chars().next().is_some_and(|c| c.is_uppercase())))
            .collect();
        let b: Vec<(String, String, u32)> =
            bigrams.iter().map(|(a, b, c)| (a.to_string(), b.to_string(), *c)).collect();
        DictData::build(&w, &b)
            .and_then(|d| Dictionary::from_data(lang, d))
            .expect("slovnik v pameti je vzdy platny")
    }

    #[inline]
    pub fn fst(&self) -> &Fst<Vec<u8>> {
        self.words.as_fst()
    }

    /// Trie slov bez diakritiky pro swipe decoder.
    #[inline]
    pub fn swipe_fst(&self) -> &Fst<Vec<u8>> {
        self.swipe.as_fst()
    }

    pub fn len(&self) -> usize {
        self.words.len()
    }

    pub fn is_empty(&self) -> bool {
        self.words.is_empty()
    }

    #[inline]
    pub fn lookup(&self, enc: &[u8]) -> Option<Entry> {
        self.words.get(enc).map(Entry::from_value)
    }

    pub fn lookup_str(&self, word: &str) -> Option<Entry> {
        charmap::encode_word(word).and_then(|e| self.lookup(&e))
    }

    /// Nejcastejsi nasledujici slova po `w1` (zakodovane).
    pub fn bigrams(&self, w1: &[u8]) -> Vec<Vec<u8>> {
        match self.bigram_idx.get(w1) {
            Some(off) => read_list(&self.bigram_blob, off as usize),
            None => Vec::new(),
        }
    }

    /// Nejcastejsi slova pro predponu bez diakritiky o delce 0..=PREFIX_TABLE_LEN.
    pub fn top_for_prefix(&self, folded_prefix: &[u8]) -> Vec<Vec<u8>> {
        match self.prefix_idx.get(folded_prefix) {
            Some(off) => read_list(&self.prefix_blob, off as usize),
            None => Vec::new(),
        }
    }

    /// Text slova vcetne velkeho pismene u vlastnich jmen.
    pub fn render(&self, enc: &[u8]) -> String {
        let s = charmap::decode_word(enc);
        match self.lookup(enc) {
            Some(e) if e.cap => charmap::capitalize(&s),
            _ => s,
        }
    }

    /// Projde cestu `enc` od korene. Vraci (adresa uzlu, soucet vystupu),
    /// nebo `None`, kdyz cesta v trie neexistuje.
    pub fn walk(&self, enc: &[u8]) -> Option<(usize, u64)> {
        let fst = self.fst();
        let mut node = fst.root();
        let mut out = Output::zero();
        for &b in enc {
            let i = node.find_input(b)?;
            let t = node.transition(i);
            out = out.cat(t.out);
            node = fst.node(t.addr);
        }
        Some((node.addr(), out.value()))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample() -> Dictionary {
        Dictionary::from_words_bigrams(
            "cs",
            &[("být", 230), ("byt", 150), ("Praha", 170), ("pes", 140), ("příliš", 170), ("prosím", 180), ("den", 190)],
            &[("dobrý", "den", 100), ("být", "pes", 5), ("být", "den", 9)],
        )
    }

    #[test]
    fn trie_exact_lookup() {
        let d = sample();
        assert_eq!(d.lookup_str("být").map(|e| e.freq), Some(230));
        assert_eq!(d.lookup_str("byt").map(|e| e.freq), Some(150));
        assert!(d.lookup_str("bytt").is_none());
        assert_eq!(d.len(), 7);
    }

    #[test]
    fn proper_noun_flag_and_render() {
        let d = sample();
        let enc = charmap::encode_word("praha").unwrap();
        assert!(d.lookup(&enc).unwrap().cap);
        assert_eq!(d.render(&enc), "Praha");
        assert_eq!(d.render(&charmap::encode_word("pes").unwrap()), "pes");
    }

    #[test]
    fn prefix_table_is_accent_insensitive_and_sorted() {
        let d = sample();
        let top: Vec<String> = d.top_for_prefix(b"pr").iter().map(|w| charmap::decode_word(w)).collect();
        assert_eq!(top, vec!["prosím", "praha", "příliš"]);
        let all = d.top_for_prefix(b"");
        assert_eq!(charmap::decode_word(&all[0]), "být");
    }

    #[test]
    fn bigrams_only_for_known_words() {
        let d = sample();
        let enc = charmap::encode_word("být").unwrap();
        let next: Vec<String> = d.bigrams(&enc).iter().map(|w| charmap::decode_word(w)).collect();
        assert_eq!(next, vec!["den", "pes"]);
        // "dobrý" neni ve slovniku -> bigram se zahodi
        assert!(d.bigrams(&charmap::encode_word("dobrý").unwrap()).is_empty());
    }

    #[test]
    fn file_roundtrip() {
        let words = vec![("ahoj".to_string(), 120u8, false), ("svět".to_string(), 130, false)];
        let data = DictData::build(&words, &[("ahoj".into(), "svět".into(), 3)]).unwrap();
        let path = std::env::temp_dir().join(format!("rb_dict_test_{}.bin", std::process::id()));
        write_file(&path, &[("cs", &data)]).unwrap();
        let loaded = load_file(&path).unwrap();
        let _ = std::fs::remove_file(&path);
        assert_eq!(loaded.len(), 1);
        assert_eq!(loaded[0].lang, "cs");
        assert_eq!(loaded[0].lookup_str("svět").map(|e| e.freq), Some(130));
        assert_eq!(loaded[0].bigrams(&charmap::encode_word("ahoj").unwrap()).len(), 1);
    }

    #[test]
    fn load_rejects_garbage() {
        let path = std::env::temp_dir().join(format!("rb_dict_bad_{}.bin", std::process::id()));
        std::fs::write(&path, b"nonsense").unwrap();
        assert!(load_file(&path).is_err());
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn walk_follows_path() {
        let d = sample();
        assert!(d.walk(&charmap::encode_word("pe").unwrap()).is_some());
        assert!(d.walk(&charmap::encode_word("xq").unwrap()).is_none());
    }
}
