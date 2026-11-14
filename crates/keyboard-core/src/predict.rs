//! Navrhy pri psani: doplnovani slova, varianty s diakritikou, autocorrect (edit distance <= 2)
//! a predikce dalsiho slova (bigramy vestaveneho slovniku + osobni bigramy).
//!
//! Autocorrect nikdy nic neprepisuje sam - opravy se jen nabizeji v suggestion baru.

use crate::charmap::{self, fold_byte, variants, APOSTROPHE};
use crate::dictionary::{Dictionary, Entry, PREFIX_TABLE_LEN};
use crate::learning::UserDict;
use fst::raw::{Fst, Node};

pub const MAX_SUGGESTIONS: usize = 3;
pub const MAX_EDIT_DISTANCE: u8 = 2;
/// Strop poctu navstivenych uzlu trie na jedno hledani (drzi predikci hluboko pod 50 ms).
const NODE_BUDGET: u32 = 60_000;
const COLLECT_TOP: usize = 12;

pub struct Context<'a> {
    pub dicts: &'a [&'a Dictionary],
    pub user: &'a UserDict,
    pub now: i64,
    pub autocorrect: bool,
    pub predictions: bool,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Found {
    pub enc: Vec<u8>,
    pub entry: Entry,
    /// Pocet rozdilu oproti napsanemu textu (diakritika / editacni vzdalenost).
    pub dist: u8,
}

/// Smi `word_byte` stat na miste napsaneho `typed_byte`?
/// Pismeno bez diakritiky odpovida vsem svym variantam, pismeno s diakritikou jen sobe.
#[inline]
fn compatible(typed_byte: u8, word_byte: u8) -> bool {
    typed_byte == word_byte || (typed_byte < 0x80 && fold_byte(word_byte) == typed_byte)
}

fn keep_top(out: &mut Vec<Found>, f: Found, limit: usize) {
    if out.len() < limit {
        out.push(f);
    } else if let Some(min) = out.iter_mut().min_by_key(|x| x.entry.freq) {
        if f.entry.freq > min.entry.freq {
            *min = f;
        }
    }
}

// ---------------------------------------------------------------- varianty s diakritikou

fn variants_rec(fst: &Fst<Vec<u8>>, node: Node<'_>, typed: &[u8], pos: usize, out_sum: u64, dist: u8, buf: &mut Vec<u8>, res: &mut Vec<Found>) {
    if pos == typed.len() {
        if node.is_final() {
            let value = out_sum + node.final_output().value();
            res.push(Found { enc: buf.clone(), entry: Entry::from_value(value), dist });
        }
        return;
    }
    let t = typed[pos];
    // Vynechany apostrof ("dont" -> "don't").
    if t != APOSTROPHE && pos > 0 {
        if let Some(i) = node.find_input(APOSTROPHE) {
            let tr = node.transition(i);
            buf.push(APOSTROPHE);
            variants_rec(fst, fst.node(tr.addr), typed, pos, out_sum + tr.out.value(), dist + 1, buf, res);
            buf.pop();
        }
    }
    let try_byte = |b: u8, buf: &mut Vec<u8>, res: &mut Vec<Found>| {
        if let Some(i) = node.find_input(b) {
            let tr = node.transition(i);
            buf.push(b);
            let d = dist + (b != t) as u8;
            variants_rec(fst, fst.node(tr.addr), typed, pos + 1, out_sum + tr.out.value(), d, buf, res);
            buf.pop();
        }
    };
    if t < 0x80 {
        for v in variants(t) {
            try_byte(v, buf, res);
        }
    } else {
        try_byte(t, buf, res);
    }
}

/// Slova, ktera se od napsaneho textu lisi jen diakritikou (vcetne presne shody, dist = 0).
pub fn accent_variants(dict: &Dictionary, typed: &[u8]) -> Vec<Found> {
    let mut res = Vec::new();
    let mut buf = Vec::with_capacity(typed.len() + 2);
    let fst = dict.fst();
    variants_rec(fst, fst.root(), typed, 0, 0, 0, &mut buf, &mut res);
    res
}

// ---------------------------------------------------------------- doplnovani

fn subtree(fst: &Fst<Vec<u8>>, node: Node<'_>, out_sum: u64, buf: &mut Vec<u8>, budget: &mut u32, res: &mut Vec<Found>, limit: usize) {
    if *budget == 0 {
        return;
    }
    *budget -= 1;
    if node.is_final() {
        let value = out_sum + node.final_output().value();
        keep_top(res, Found { enc: buf.clone(), entry: Entry::from_value(value), dist: 0 }, limit);
    }
    if buf.len() >= charmap::MAX_WORD {
        return;
    }
    for tr in node.transitions() {
        buf.push(tr.inp);
        subtree(fst, fst.node(tr.addr), out_sum + tr.out.value(), buf, budget, res, limit);
        buf.pop();
    }
}

fn prefix_rec(fst: &Fst<Vec<u8>>, node: Node<'_>, typed: &[u8], pos: usize, out_sum: u64, buf: &mut Vec<u8>, budget: &mut u32, res: &mut Vec<Found>, limit: usize) {
    if pos == typed.len() {
        subtree(fst, node, out_sum, buf, budget, res, limit);
        return;
    }
    let t = typed[pos];
    for tr in node.transitions() {
        if compatible(t, tr.inp) {
            buf.push(tr.inp);
            prefix_rec(fst, fst.node(tr.addr), typed, pos + 1, out_sum + tr.out.value(), buf, budget, res, limit);
            buf.pop();
        }
    }
}

/// Nejcastejsi slova zacinajici napsanou predponou (diakritika se doplni sama).
pub fn completions(dict: &Dictionary, typed: &[u8], limit: usize) -> Vec<Found> {
    let mut res = Vec::new();
    if typed.len() <= PREFIX_TABLE_LEN {
        // Kratke predpony: predpocitana tabulka (podstrom by byl obrovsky).
        let folded = charmap::fold_bytes(typed);
        for enc in dict.top_for_prefix(&folded) {
            if enc.len() >= typed.len() && typed.iter().zip(&enc).all(|(&t, &w)| compatible(t, w)) {
                if let Some(entry) = dict.lookup(&enc) {
                    res.push(Found { enc, entry, dist: 0 });
                }
            }
        }
        res.truncate(limit);
        return res;
    }
    let fst = dict.fst();
    let mut buf = Vec::with_capacity(charmap::MAX_WORD + 1);
    let mut budget = NODE_BUDGET;
    prefix_rec(fst, fst.root(), typed, 0, 0, &mut buf, &mut budget, &mut res, limit);
    res.sort_by(|a, b| b.entry.freq.cmp(&a.entry.freq).then(a.enc.len().cmp(&b.enc.len())));
    res
}

// ---------------------------------------------------------------- fuzzy (edit distance)

struct Fuzzy<'a> {
    fst: &'a Fst<Vec<u8>>,
    typed: &'a [u8],
    max: u8,
    /// Radky DP matice, jeden na hloubku rekurze (zadne alokace behem hledani).
    rows: Vec<u8>,
    width: usize,
    budget: u32,
    buf: Vec<u8>,
    res: Vec<Found>,
}

impl Fuzzy<'_> {
    fn go(&mut self, node: Node<'_>, depth: usize, out_sum: u64) {
        if self.budget == 0 || depth >= self.typed.len() + self.max as usize {
            return;
        }
        self.budget -= 1;
        let w = self.width;
        for tr in node.transitions() {
            // Prvni pismeno musi sedet (az na diakritiku) - zasadne to zmensuje prohledavany prostor.
            if depth == 0 && fold_byte(tr.inp) != fold_byte(self.typed[0]) {
                continue;
            }
            let (prev_rows, cur_rows) = self.rows.split_at_mut((depth + 1) * w);
            let prev = &prev_rows[depth * w..];
            let cur = &mut cur_rows[..w];
            cur[0] = prev[0].saturating_add(1);
            let mut min = cur[0];
            for j in 1..w {
                let sub = (fold_byte(self.typed[j - 1]) != fold_byte(tr.inp)) as u8;
                let v = (prev[j - 1] + sub).min(prev[j].saturating_add(1)).min(cur[j - 1].saturating_add(1));
                cur[j] = v;
                min = min.min(v);
            }
            if min > self.max {
                continue;
            }
            let d = cur[w - 1];
            let child = self.fst.node(tr.addr);
            let sum = out_sum + tr.out.value();
            self.buf.push(tr.inp);
            if d <= self.max && child.is_final() {
                let value = sum + child.final_output().value();
                let f = Found { enc: self.buf.clone(), entry: Entry::from_value(value), dist: d };
                // Nejdriv mensi vzdalenost, pak frekvence.
                if self.res.len() < 4 * COLLECT_TOP {
                    self.res.push(f);
                } else if let Some(worst) = self.res.iter_mut().max_by_key(|x| (x.dist, 255 - x.entry.freq)) {
                    if (f.dist, 255 - f.entry.freq) < (worst.dist, 255 - worst.entry.freq) {
                        *worst = f;
                    }
                }
            }
            self.go(child, depth + 1, sum);
            self.buf.pop();
        }
    }
}

/// Slova s editacni vzdalenosti <= `max_dist` od napsaneho textu (rozdil v diakritice se nepocita).
pub fn fuzzy(dict: &Dictionary, typed: &[u8], max_dist: u8) -> Vec<Found> {
    if typed.is_empty() || typed.len() > charmap::MAX_WORD {
        return Vec::new();
    }
    let width = typed.len() + 1;
    let depth_max = typed.len() + max_dist as usize + 1;
    let mut f = Fuzzy {
        fst: dict.fst(),
        typed,
        max: max_dist,
        rows: vec![0u8; width * (depth_max + 1)],
        width,
        budget: NODE_BUDGET,
        buf: Vec::with_capacity(depth_max),
        res: Vec::new(),
    };
    for j in 0..width {
        f.rows[j] = j as u8;
    }
    let root = f.fst.root();
    f.go(root, 0, 0);
    f.res.sort_by(|a, b| a.dist.cmp(&b.dist).then(b.entry.freq.cmp(&a.entry.freq)));
    f.res
}

// ---------------------------------------------------------------- skladani navrhu

fn quality(freq: u8, personal: f32) -> f32 {
    // Stejny pomer vah jako u swipe: jazyk 0.25, osobni frekvence 0.15.
    (0.25 * (freq as f32 / 255.0) + 0.15 * personal) / 0.40
}

struct Pool {
    items: Vec<(String, f32)>,
}

impl Pool {
    fn add(&mut self, word: String, score: f32) {
        let key = word.to_lowercase();
        match self.items.iter_mut().find(|(w, _)| w.to_lowercase() == key) {
            Some(e) => {
                if score > e.1 {
                    *e = (word, score);
                }
            }
            None => self.items.push((word, score)),
        }
    }

    fn top(mut self, n: usize) -> Vec<String> {
        self.items.sort_by(|a, b| b.1.total_cmp(&a.1));
        self.items.into_iter().take(n).map(|(w, _)| w).collect()
    }
}

fn render(enc: &[u8], entry: Entry) -> String {
    let s = charmap::decode_word(enc);
    if entry.cap {
        charmap::capitalize(&s)
    } else {
        s
    }
}

fn apply_case(word: String, typed: &str) -> String {
    let mut chars = typed.chars();
    let first_upper = chars.next().is_some_and(|c| c.is_uppercase());
    let all_upper = first_upper && typed.chars().count() > 1 && typed.chars().all(|c| !c.is_lowercase());
    if all_upper {
        word.to_uppercase()
    } else if first_upper {
        charmap::capitalize(&word)
    } else {
        word
    }
}

/// Je slovo zname (vestaveny nebo osobni slovnik)?
pub fn is_known(ctx: &Context, word: &str) -> bool {
    if ctx.user.get(word).is_some() || ctx.user.get(&word.to_lowercase()).is_some() {
        return true;
    }
    match charmap::encode_word(word) {
        Some(enc) => ctx.dicts.iter().any(|d| d.lookup(&enc).is_some()),
        None => false,
    }
}

/// Navrhy pro rozepsane slovo: nejvyse 3. Nezname napsane slovo je vzdy prvni
/// (aby ho slo potvrdit tak, jak je), opravy a doplneni nasleduji.
pub fn suggest_composing(ctx: &Context, typed: &str) -> Vec<String> {
    if typed.is_empty() {
        return Vec::new();
    }
    let Some(enc) = charmap::encode_word(typed) else {
        return vec![typed.to_string()];
    };
    let typed_lower = typed.to_lowercase();
    let mut pool = Pool { items: Vec::new() };
    let mut exact_known = false;

    for dict in ctx.dicts {
        for f in accent_variants(dict, &enc) {
            let word = render(&f.enc, f.entry);
            let q = quality(f.entry.freq, ctx.user.strength(&word, ctx.now));
            if f.dist == 0 {
                exact_known = true;
                pool.add(word, 1.0 + q);
            } else if ctx.autocorrect {
                pool.add(word, 0.75 + q - 0.03 * f.dist as f32);
            }
        }
        if ctx.predictions {
            for f in completions(dict, &enc, COLLECT_TOP) {
                if f.enc.len() == enc.len() {
                    continue; // presna delka = varianta, uz zapocitana vyse
                }
                let word = render(&f.enc, f.entry);
                let q = quality(f.entry.freq, ctx.user.strength(&word, ctx.now));
                pool.add(word, 0.35 + 0.5 * q);
            }
        }
    }

    // Osobni slova (i ta mimo vestaveny slovnik).
    let folded = charmap::fold_bytes(&enc);
    for (w, uw) in ctx.user.iter() {
        let Some(wenc) = charmap::encode_word(w) else { continue };
        let s = crate::learning::strength_of(*uw, ctx.now);
        if wenc == enc {
            exact_known = true;
            pool.add(w.clone(), 1.0 + quality(USER_FREQ, s));
        } else if ctx.predictions && wenc.len() > enc.len() && charmap::fold_bytes(&wenc[..enc.len()]) == folded {
            pool.add(w.clone(), 0.45 + 0.5 * quality(USER_FREQ, s));
        }
    }

    // Autocorrect: jen kdyz napsane slovo nezname.
    if ctx.autocorrect && !exact_known && enc.len() >= 3 {
        let max = if enc.len() >= 5 { MAX_EDIT_DISTANCE } else { 1 };
        for dict in ctx.dicts {
            for f in fuzzy(dict, &enc, max).into_iter().take(COLLECT_TOP) {
                if f.dist == 0 {
                    continue;
                }
                let word = render(&f.enc, f.entry);
                let q = quality(f.entry.freq, ctx.user.strength(&word, ctx.now));
                let base = if f.dist == 1 { 0.30 } else { 0.10 };
                pool.add(word, base + 0.4 * q);
            }
        }
    }

    let mut out: Vec<String> = Vec::with_capacity(MAX_SUGGESTIONS);
    if !exact_known {
        out.push(typed.to_string());
    }
    for w in pool.top(MAX_SUGGESTIONS + 1) {
        let w = apply_case(w, typed);
        if out.len() < MAX_SUGGESTIONS && !out.contains(&w) && (exact_known || w.to_lowercase() != typed_lower) {
            out.push(w);
        }
    }
    out
}

/// Frekvence pripsana osobnim slovum bez zaznamu ve vestavenem slovniku.
const USER_FREQ: u8 = 110;

/// Predikce dalsiho slova po `prev` (prazdne `prev` = zacatek vety). `prev2` je slovo pred nim
/// (prazdne, kdyz neni): dvojice slov ma prednost (trigramy), pak jedno slovo (bigramy),
/// nakonec nejcastejsi slova jazyka. Osobni data maji vzdy prednost pred vestavenymi.
pub fn predict_next(ctx: &Context, prev2: &str, prev: &str, capitalize: bool) -> Vec<String> {
    if !ctx.predictions {
        return Vec::new();
    }
    let mut pool = Pool { items: Vec::new() };
    let prev_lower = prev.to_lowercase();
    let prev2_lower = prev2.to_lowercase();

    for (i, (w, count)) in ctx.user.next_after(&prev2_lower, &prev_lower).into_iter().enumerate().take(6) {
        pool.add(w, 3.0 + (count.min(50) as f32) * 0.02 - i as f32 * 0.001);
    }
    if let (Some(e2), Some(e1)) = (charmap::encode_word(&prev2_lower), charmap::encode_word(&prev_lower)) {
        for dict in ctx.dicts {
            for (i, next) in dict.trigrams(&e2, &e1).iter().enumerate() {
                let word = dict.render(next);
                let p = ctx.user.strength(&word, ctx.now);
                pool.add(word, 1.6 - i as f32 * 0.05 + 0.3 * p);
            }
        }
    }

    for (i, (w, count)) in ctx.user.next_words(&prev_lower).into_iter().enumerate().take(6) {
        pool.add(w, 2.0 + (count.min(50) as f32) * 0.02 - i as f32 * 0.001);
    }
    let prev_enc = charmap::encode_word(&prev_lower);
    for dict in ctx.dicts {
        if let Some(enc) = &prev_enc {
            for (i, next) in dict.bigrams(enc).iter().enumerate() {
                let word = dict.render(next);
                let p = ctx.user.strength(&word, ctx.now);
                pool.add(word, 1.0 - i as f32 * 0.05 + 0.3 * p);
            }
        }
        // Zaloha: nejcastejsi slova jazyka.
        for (i, w) in dict.top_for_prefix(&[]).iter().enumerate() {
            pool.add(dict.render(w), 0.3 - i as f32 * 0.02);
        }
    }
    pool.top(MAX_SUGGESTIONS)
        .into_iter()
        .map(|w| if capitalize { charmap::capitalize(&w) } else { w })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    const NOW: i64 = 1_800_000_000_000;

    fn dict() -> Dictionary {
        Dictionary::from_words_bigrams(
            "cs",
            &[
                ("být", 230), ("byt", 150), ("příliš", 170), ("přítel", 150), ("přítelkyně", 120),
                ("prosím", 180), ("Praha", 170), ("pes", 140), ("den", 190), ("dobrý", 175),
                ("večer", 150), ("klávesnice", 120), ("klávesa", 110), ("a", 240), ("se", 235), ("v", 238),
                ("děkuji", 150), ("don't", 160),
            ],
            &[("dobrý", "den", 100), ("dobrý", "večer", 40), ("v", "Praha", 5)],
        )
    }

    fn ctx<'a>(dicts: &'a [&'a Dictionary], user: &'a UserDict) -> Context<'a> {
        Context { dicts, user, now: NOW, autocorrect: true, predictions: true }
    }

    fn enc(s: &str) -> Vec<u8> {
        charmap::encode_word(s).unwrap()
    }

    #[test]
    fn accent_variants_restore_diacritics() {
        let d = dict();
        let v = accent_variants(&d, &enc("prilis"));
        assert_eq!(v.len(), 1);
        assert_eq!(charmap::decode_word(&v[0].enc), "příliš");
        assert_eq!(v[0].dist, 3);
        let both: Vec<String> = accent_variants(&d, &enc("byt")).iter().map(|f| charmap::decode_word(&f.enc)).collect();
        assert!(both.contains(&"byt".to_string()) && both.contains(&"být".to_string()));
    }

    #[test]
    fn typed_accent_is_respected() {
        let d = dict();
        // "být" napsane s diakritikou nesmi nabidnout "byt"
        let v: Vec<String> = accent_variants(&d, &enc("být")).iter().map(|f| charmap::decode_word(&f.enc)).collect();
        assert_eq!(v, vec!["být"]);
    }

    #[test]
    fn missing_apostrophe_is_restored() {
        let d = dict();
        let v: Vec<String> = accent_variants(&d, &enc("dont")).iter().map(|f| charmap::decode_word(&f.enc)).collect();
        assert_eq!(v, vec!["don't"]);
    }

    #[test]
    fn completions_short_prefix_use_table() {
        let d = dict();
        let v: Vec<String> = completions(&d, &enc("pr"), 8).iter().map(|f| charmap::decode_word(&f.enc)).collect();
        assert_eq!(v[0], "prosím");
        assert!(v.contains(&"příliš".to_string()) && v.contains(&"praha".to_string()));
    }

    #[test]
    fn completions_long_prefix_walk_trie_with_accents() {
        let d = dict();
        let v: Vec<String> = completions(&d, &enc("prit"), 8).iter().map(|f| charmap::decode_word(&f.enc)).collect();
        assert_eq!(v, vec!["přítel", "přítelkyně"]);
        assert!(completions(&d, &enc("xyzq"), 8).is_empty());
    }

    #[test]
    fn fuzzy_finds_within_distance_two() {
        let d = dict();
        let names = |v: Vec<Found>| v.into_iter().map(|f| (charmap::decode_word(&f.enc), f.dist)).collect::<Vec<_>>();
        assert!(names(fuzzy(&d, &enc("klavesnicw"), 2)).contains(&("klávesnice".to_string(), 1)));
        assert!(names(fuzzy(&d, &enc("klvesnce"), 2)).contains(&("klávesnice".to_string(), 2)));
        assert!(names(fuzzy(&d, &enc("klvsnc"), 2)).iter().all(|(w, _)| w != "klávesnice"));
    }

    #[test]
    fn unknown_word_is_first_then_correction() {
        let d = dict();
        let u = UserDict::new();
        let dicts = [&d];
        let s = suggest_composing(&ctx(&dicts, &u), "prilis");
        assert_eq!(s[0], "prilis");
        assert_eq!(s[1], "příliš");
        let s = suggest_composing(&ctx(&dicts, &u), "klavesnicw");
        assert_eq!(s[0], "klavesnicw");
        assert!(s.contains(&"klávesnice".to_string()));
    }

    #[test]
    fn known_word_offers_accented_twin_and_completion() {
        let d = dict();
        let u = UserDict::new();
        let dicts = [&d];
        let s = suggest_composing(&ctx(&dicts, &u), "byt");
        assert_eq!(s.len(), 2);
        assert!(s.contains(&"byt".to_string()) && s.contains(&"být".to_string()));
        let s = suggest_composing(&ctx(&dicts, &u), "kláves");
        assert!(s.contains(&"klávesnice".to_string()) && s.contains(&"klávesa".to_string()));
    }

    #[test]
    fn suggestions_follow_typed_case() {
        let d = dict();
        let u = UserDict::new();
        let dicts = [&d];
        let s = suggest_composing(&ctx(&dicts, &u), "Prosi");
        assert!(s.contains(&"Prosím".to_string()), "{s:?}");
        let s = suggest_composing(&ctx(&dicts, &u), "prah");
        assert!(s.contains(&"Praha".to_string()), "{s:?}");
    }

    #[test]
    fn autocorrect_and_predictions_can_be_disabled() {
        let d = dict();
        let u = UserDict::new();
        let dicts = [&d];
        let mut c = ctx(&dicts, &u);
        c.autocorrect = false;
        c.predictions = false;
        assert_eq!(suggest_composing(&c, "prilis"), vec!["prilis"]);
        assert!(predict_next(&c, "", "dobrý", false).is_empty());
    }

    #[test]
    fn non_alphabet_input_is_returned_verbatim() {
        let d = dict();
        let u = UserDict::new();
        let dicts = [&d];
        assert_eq!(suggest_composing(&ctx(&dicts, &u), "abc123"), vec!["abc123"]);
        assert!(suggest_composing(&ctx(&dicts, &u), "").is_empty());
    }

    #[test]
    fn next_word_from_builtin_bigrams() {
        let d = dict();
        let u = UserDict::new();
        let dicts = [&d];
        let p = predict_next(&ctx(&dicts, &u), "", "Dobrý", false);
        assert_eq!(p[..2], ["den", "večer"]);
        assert_eq!(p.len(), 3);
        assert_eq!(predict_next(&ctx(&dicts, &u), "", "v", false)[0], "Praha");
    }

    #[test]
    fn next_word_prefers_personal_bigrams() {
        let d = dict();
        let mut u = UserDict::new();
        u.learn("pes", "dobrý", NOW);
        let dicts = [&d];
        let p = predict_next(&ctx(&dicts, &u), "", "dobrý", false);
        assert_eq!(p[0], "pes");
        assert_eq!(p[1], "den");
    }

    #[test]
    fn two_word_context_beats_one_word() {
        let words: Vec<(String, u8, bool)> =
            ["jak", "se", "máš", "to", "v", "a"].iter().map(|w| (w.to_string(), 200u8, false)).collect();
        let data = crate::dictionary::DictData::build_with_trigrams(
            &words,
            &[("se".into(), "to".into(), 50)],
            &[("jak".into(), "se".into(), "máš".into(), 20)],
        )
        .unwrap();
        let d = Dictionary::from_data("cs", data).unwrap();
        let mut u = UserDict::new();
        let dicts = [&d];
        // jen jedno slovo kontextu -> bigram
        assert_eq!(predict_next(&ctx(&dicts, &u), "", "se", false)[0], "to");
        // dve slova -> trigram ma prednost
        assert_eq!(predict_next(&ctx(&dicts, &u), "Jak", "se", false)[0], "máš");
        // osobni trigram ma prednost pred vestavenym
        u.learn_trigram("jak", "se", "v");
        assert_eq!(predict_next(&ctx(&dicts, &u), "jak", "se", false)[..2], ["v", "máš"]);
    }

    #[test]
    fn sentence_start_predictions_are_capitalized_fallbacks() {
        let d = dict();
        let u = UserDict::new();
        let dicts = [&d];
        let p = predict_next(&ctx(&dicts, &u), "", "", true);
        assert_eq!(p, vec!["A", "V", "Se"]);
    }

    #[test]
    fn personal_words_complete_and_count_as_known() {
        let d = dict();
        let mut u = UserDict::new();
        u.learn("Rustboard", "", NOW);
        let dicts = [&d];
        let c = ctx(&dicts, &u);
        assert!(suggest_composing(&c, "rustb").contains(&"Rustboard".to_string()));
        assert!(is_known(&c, "Rustboard"));
        assert!(is_known(&c, "pes"));
        assert!(!is_known(&c, "xqzw"));
    }
}
