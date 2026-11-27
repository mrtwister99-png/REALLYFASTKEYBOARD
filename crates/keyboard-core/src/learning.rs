//! Uceni z psani: osobni slovnik (pocet pouziti + cas posledniho pouziti),
//! osobni bigramy a postupne zapominani. Vse zustava v zarizeni.

use serde::{Deserialize, Serialize};
use std::collections::HashMap;

const DAY_MS: i64 = 86_400_000;
/// Polocas rozpadu osobni frekvence.
const HALF_LIFE_DAYS: f32 = 90.0;
/// Pocet pouziti, pri kterem je osobni frekvence nasycena.
const SATURATION: f32 = 30.0;
const MAX_FOLLOWERS: usize = 12;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub struct UserWord {
    pub count: u32,
    pub last_used: i64,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ExportWord {
    pub word: String,
    pub count: u32,
    pub last_used: i64,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ExportBigram {
    pub w1: String,
    pub w2: String,
    pub count: u32,
}

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct Export {
    pub version: u32,
    pub words: Vec<ExportWord>,
    pub bigrams: Vec<ExportBigram>,
    /// Trigramy: `w1` = kontext "predposledni posledni", `w2` = nasledujici slovo.
    pub trigrams: Vec<ExportBigram>,
}

/// Co se ma po nauceni zapsat do uloziste.
#[derive(Debug, Clone, PartialEq)]
pub struct Learned {
    pub word: String,
    pub count: u32,
    pub bigram: Option<(String, String, u32)>,
}

#[derive(Debug, Default)]
pub struct UserDict {
    words: HashMap<String, UserWord>,
    /// Zakodovana slova (cache pro navrhy a swipe, aby se nekodovalo pri kazdem stisku).
    encs: HashMap<String, Vec<u8>>,
    bigrams: HashMap<String, Vec<(String, u32)>>,
    /// Kontext "predposledni posledni" (malymi) -> nasledujici slova.
    trigrams: HashMap<String, Vec<(String, u32)>>,
    /// Uceni z oprav: spatne slovo -> (cim ho uzivatel nahradil, kolikrat).
    /// Prazdna nahrada = slovo bylo po swipe smazano (odmitnuto).
    corrections: HashMap<String, Vec<(String, u32)>>,
}

/// Slovo vhodne k uceni: 1-30 znaku, jen pismena (a apostrof uvnitr).
pub fn is_learnable(word: &str) -> bool {
    let n = word.chars().count();
    (1..=30).contains(&n)
        && word.chars().all(|c| c.is_alphabetic() || c == '\'')
        && word.chars().next().is_some_and(|c| c.is_alphabetic())
}

/// Osobni frekvence v intervalu 0..=1: roste logaritmicky s poctem pouziti
/// a klesa s casem od posledniho pouziti (zapominani).
pub fn strength_of(w: UserWord, now: i64) -> f32 {
    let base = ((1.0 + w.count as f32).ln() / (1.0 + SATURATION).ln()).min(1.0);
    let age_days = ((now - w.last_used).max(0) / DAY_MS) as f32;
    base * 0.5f32.powf(age_days / HALF_LIFE_DAYS)
}

impl UserDict {
    pub fn new() -> UserDict {
        UserDict::default()
    }

    pub fn len(&self) -> usize {
        self.words.len()
    }

    pub fn is_empty(&self) -> bool {
        self.words.is_empty()
    }

    pub fn get(&self, word: &str) -> Option<UserWord> {
        self.words.get(word).copied()
    }

    pub fn iter(&self) -> impl Iterator<Item = (&String, &UserWord)> {
        self.words.iter()
    }

    /// Osobni slova se zakodovanym tvarem (slova, ktera nejdou zakodovat, se preskoci).
    pub fn iter_enc(&self) -> impl Iterator<Item = (&String, &UserWord, &[u8])> {
        self.words.iter().filter_map(|(w, u)| self.encs.get(w).map(|e| (w, u, e.as_slice())))
    }

    fn sync_enc(&mut self, word: &str) {
        if !self.encs.contains_key(word) {
            if let Some(e) = crate::charmap::encode_word(word) {
                self.encs.insert(word.to_string(), e);
            }
        }
    }

    pub fn insert_word(&mut self, word: &str, count: u32, last_used: i64) {
        self.words.insert(word.to_string(), UserWord { count, last_used });
        self.sync_enc(word);
    }

    pub fn insert_bigram(&mut self, w1: &str, w2: &str, count: u32) {
        let list = self.bigrams.entry(w1.to_string()).or_default();
        match list.iter_mut().find(|(w, _)| w == w2) {
            Some(e) => e.1 = count,
            None => list.push((w2.to_string(), count)),
        }
    }

    /// Zaznamena pouziti slova (a dvojice s predchozim slovem).
    pub fn learn(&mut self, word: &str, prev: &str, now: i64) -> Option<Learned> {
        if !is_learnable(word) {
            return None;
        }
        let e = self.words.entry(word.to_string()).or_insert(UserWord { count: 0, last_used: now });
        e.count = e.count.saturating_add(1);
        e.last_used = now;
        let count = e.count;
        self.sync_enc(word);
        let mut bigram = None;
        if is_learnable(prev) {
            let w1 = prev.to_lowercase();
            let list = self.bigrams.entry(w1.clone()).or_default();
            let c = match list.iter_mut().find(|(w, _)| w == word) {
                Some(e) => {
                    e.1 = e.1.saturating_add(1);
                    e.1
                }
                None => {
                    list.push((word.to_string(), 1));
                    1
                }
            };
            list.sort_by(|a, b| b.1.cmp(&a.1));
            list.truncate(MAX_FOLLOWERS);
            bigram = Some((w1, word.to_string(), c));
        }
        Some(Learned { word: word.to_string(), count, bigram })
    }

    /// Osobni frekvence slova (zkousi i variantu malymi pismeny).
    pub fn strength(&self, word: &str, now: i64) -> f32 {
        if let Some(w) = self.words.get(word) {
            return strength_of(*w, now);
        }
        let lower = word.to_lowercase();
        self.words.get(&lower).map_or(0.0, |w| strength_of(*w, now))
    }

    /// Nejcastejsi osobni nasledujici slova po `prev`.
    pub fn next_words(&self, prev: &str) -> Vec<(String, u32)> {
        let mut v = self.bigrams.get(&prev.to_lowercase()).cloned().unwrap_or_default();
        v.sort_by(|a, b| b.1.cmp(&a.1).then(a.0.cmp(&b.0)));
        v
    }

    pub fn remove(&mut self, word: &str) -> bool {
        let removed = self.words.remove(word).is_some();
        self.encs.remove(word);
        self.corrections.remove(word);
        for list in self.trigrams.values_mut() {
            list.retain(|(w, _)| w != word);
        }
        self.trigrams.retain(|_, l| !l.is_empty());
        self.bigrams.remove(&word.to_lowercase());
        for list in self.bigrams.values_mut() {
            list.retain(|(w, _)| w != word);
        }
        self.bigrams.retain(|_, l| !l.is_empty());
        removed
    }

    pub fn clear(&mut self) {
        self.words.clear();
        self.encs.clear();
        self.bigrams.clear();
        self.trigrams.clear();
        self.corrections.clear();
    }

    /// Zapominani: vrati slova, ktera byla odstranena (dlouho nepouzita).
    pub fn forget_stale(&mut self, now: i64) -> Vec<String> {
        let stale: Vec<String> = self
            .words
            .iter()
            .filter(|(_, w)| {
                let age = (now - w.last_used) / DAY_MS;
                (w.count <= 1 && age > 30) || (w.count < 5 && age > 180) || age > 365
            })
            .map(|(k, _)| k.clone())
            .collect();
        for w in &stale {
            self.remove(w);
        }
        stale
    }

    /// Seznam pro nastaveni: nejpouzivanejsi prvni.
    pub fn list(&self) -> Vec<ExportWord> {
        let mut v: Vec<ExportWord> = self
            .words
            .iter()
            .map(|(w, u)| ExportWord { word: w.clone(), count: u.count, last_used: u.last_used })
            .collect();
        v.sort_by(|a, b| b.count.cmp(&a.count).then(a.word.cmp(&b.word)));
        v
    }

    pub fn export(&self) -> Export {
        let mut bigrams: Vec<ExportBigram> = self
            .bigrams
            .iter()
            .flat_map(|(w1, l)| l.iter().map(move |(w2, c)| ExportBigram { w1: w1.clone(), w2: w2.clone(), count: *c }))
            .collect();
        bigrams.sort_by(|a, b| a.w1.cmp(&b.w1).then(a.w2.cmp(&b.w2)));
        let mut trigrams: Vec<ExportBigram> = self
            .trigrams
            .iter()
            .flat_map(|(ctx, l)| l.iter().map(move |(w, c)| ExportBigram { w1: ctx.clone(), w2: w.clone(), count: *c }))
            .collect();
        trigrams.sort_by(|a, b| a.w1.cmp(&b.w1).then(a.w2.cmp(&b.w2)));
        Export { version: 2, words: self.list(), bigrams, trigrams }
    }

    /// Slouci import do slovniku (pocet = maximum, aby opakovany import nenafukoval cisla).
    /// Vraci pocet prijatych slov.
    pub fn import(&mut self, data: &Export) -> usize {
        let mut n = 0;
        for w in &data.words {
            if !is_learnable(&w.word) || w.count == 0 {
                continue;
            }
            let e = self.words.entry(w.word.clone()).or_insert(UserWord { count: 0, last_used: w.last_used });
            e.count = e.count.max(w.count);
            e.last_used = e.last_used.max(w.last_used);
            self.sync_enc(&w.word);
            n += 1;
        }
        for b in &data.bigrams {
            if !is_learnable(&b.w1) || !is_learnable(&b.w2) || b.count == 0 {
                continue;
            }
            let list = self.bigrams.entry(b.w1.to_lowercase()).or_default();
            match list.iter_mut().find(|(w, _)| *w == b.w2) {
                Some(e) => e.1 = e.1.max(b.count),
                None => list.push((b.w2.clone(), b.count)),
            }
        }
        for t in &data.trigrams {
            if t.count == 0 || !is_learnable(&t.w2) || t.w1.split(' ').count() != 2 || !t.w1.split(' ').all(is_learnable) {
                continue;
            }
            let list = self.trigrams.entry(t.w1.to_lowercase()).or_default();
            match list.iter_mut().find(|(w, _)| *w == t.w2) {
                Some(e) => e.1 = e.1.max(t.count),
                None => list.push((t.w2.clone(), t.count)),
            }
        }
        n
    }
}

/// Trigramy a uceni z oprav.
impl UserDict {
    fn context_key(prev2: &str, prev: &str) -> String {
        format!("{} {}", prev2.to_lowercase(), prev.to_lowercase())
    }

    pub fn insert_trigram(&mut self, ctx: &str, word: &str, count: u32) {
        let list = self.trigrams.entry(ctx.to_string()).or_default();
        match list.iter_mut().find(|(w, _)| w == word) {
            Some(e) => e.1 = count,
            None => list.push((word.to_string(), count)),
        }
    }

    /// Zaznamena trojici (prev2, prev) -> word. Vraci (kontext, slovo, pocet) k ulozeni.
    pub fn learn_trigram(&mut self, prev2: &str, prev: &str, word: &str) -> Option<(String, String, u32)> {
        if !is_learnable(prev2) || !is_learnable(prev) || !is_learnable(word) {
            return None;
        }
        let ctx = Self::context_key(prev2, prev);
        let list = self.trigrams.entry(ctx.clone()).or_default();
        let c = match list.iter_mut().find(|(w, _)| w == word) {
            Some(e) => {
                e.1 = e.1.saturating_add(1);
                e.1
            }
            None => {
                list.push((word.to_string(), 1));
                1
            }
        };
        list.sort_by(|a, b| b.1.cmp(&a.1));
        list.truncate(MAX_FOLLOWERS);
        Some((ctx, word.to_string(), c))
    }

    /// Osobni nasledujici slova po dvojici slov.
    pub fn next_after(&self, prev2: &str, prev: &str) -> Vec<(String, u32)> {
        if prev2.is_empty() || prev.is_empty() {
            return Vec::new();
        }
        let mut v = self.trigrams.get(&Self::context_key(prev2, prev)).cloned().unwrap_or_default();
        v.sort_by(|a, b| b.1.cmp(&a.1).then(a.0.cmp(&b.0)));
        v
    }

    pub fn insert_correction(&mut self, bad: &str, good: &str, count: u32) {
        let list = self.corrections.entry(bad.to_string()).or_default();
        match list.iter_mut().find(|(g, _)| g == good) {
            Some(e) => e.1 = count,
            None => list.push((good.to_string(), count)),
        }
    }

    /// Uzivatel nahradil `bad` slovem `good` (nebo ho smazal, kdyz je `good` prazdne).
    /// Vraci novy pocet k ulozeni.
    pub fn note_correction(&mut self, bad: &str, good: &str) -> u32 {
        let list = self.corrections.entry(bad.to_string()).or_default();
        match list.iter_mut().find(|(g, _)| g == good) {
            Some(e) => {
                e.1 = e.1.saturating_add(1);
                e.1
            }
            None => {
                list.push((good.to_string(), 1));
                1
            }
        }
    }

    /// Vsechny opravy (spatne, spravne, pocet) setridene; nulove pocty se vynechaji.
    pub fn corrections_list(&self) -> Vec<(String, String, u32)> {
        let mut v: Vec<(String, String, u32)> = self
            .corrections
            .iter()
            .flat_map(|(bad, l)| l.iter().filter(|(_, c)| *c > 0).map(move |(g, c)| (bad.clone(), g.clone(), *c)))
            .collect();
        v.sort();
        v
    }

    /// Slouci opravu ze zalohy (pocet = maximum). Vraci novy pocet k ulozeni.
    pub fn merge_correction(&mut self, bad: &str, good: &str, count: u32) -> u32 {
        let list = self.corrections.entry(bad.to_string()).or_default();
        match list.iter_mut().find(|(g, _)| g == good) {
            Some(e) => {
                e.1 = e.1.max(count);
                e.1
            }
            None => {
                list.push((good.to_string(), count));
                count
            }
        }
    }

    /// Kolikrat bylo slovo po swipe smazano.
    pub fn rejections(&self, word: &str) -> u32 {
        self.corrections.get(word).and_then(|l| l.iter().find(|(g, _)| g.is_empty())).map_or(0, |e| e.1)
    }

    /// Cim uzivatel slovo nahrazoval (bez prazdnych = smazani).
    pub fn corrections_from(&self, bad: &str) -> impl Iterator<Item = (&str, u32)> {
        self.corrections.get(bad).into_iter().flatten().filter(|(g, _)| !g.is_empty()).map(|(g, c)| (g.as_str(), *c))
    }

    /// Slovo bylo normalne potvrzeno: jedno drivejsi odmitnuti se odpusti.
    /// Vraci novy pocet odmitnuti, pokud se zmenil.
    pub fn forgive(&mut self, word: &str) -> Option<u32> {
        let e = self.corrections.get_mut(word)?.iter_mut().find(|(g, _)| g.is_empty())?;
        if e.1 == 0 {
            return None;
        }
        e.1 -= 1;
        Some(e.1)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const NOW: i64 = 1_800_000_000_000;

    #[test]
    fn learning_increments_count() {
        let mut u = UserDict::new();
        assert_eq!(u.learn("ahoj", "", NOW).unwrap().count, 1);
        assert_eq!(u.learn("ahoj", "", NOW + 5).unwrap().count, 2);
        assert_eq!(u.get("ahoj"), Some(UserWord { count: 2, last_used: NOW + 5 }));
    }

    #[test]
    fn rejects_non_words() {
        let mut u = UserDict::new();
        assert!(u.learn("abc123", "", NOW).is_none());
        assert!(u.learn("", "", NOW).is_none());
        assert!(u.learn("'", "", NOW).is_none());
        assert!(u.learn("heslo!", "", NOW).is_none());
        assert!(u.is_empty());
    }

    #[test]
    fn strength_grows_with_use_and_saturates() {
        let one = strength_of(UserWord { count: 1, last_used: NOW }, NOW);
        let ten = strength_of(UserWord { count: 10, last_used: NOW }, NOW);
        let many = strength_of(UserWord { count: 10_000, last_used: NOW }, NOW);
        assert!(one > 0.0 && one < ten && ten < many);
        assert_eq!(many, 1.0);
    }

    #[test]
    fn strength_decays_over_time() {
        let w = UserWord { count: 10, last_used: NOW };
        let fresh = strength_of(w, NOW);
        let old = strength_of(w, NOW + 90 * DAY_MS);
        assert!((old - fresh * 0.5).abs() < 1e-4);
        assert!(strength_of(w, NOW - 1000) <= fresh + 1e-6); // hodiny zpet nevadi
    }

    #[test]
    fn bigrams_are_learned_and_ranked() {
        let mut u = UserDict::new();
        u.learn("den", "Dobrý", NOW);
        u.learn("den", "dobrý", NOW);
        u.learn("večer", "dobrý", NOW);
        let next = u.next_words("DOBRÝ");
        assert_eq!(next[0], ("den".to_string(), 2));
        assert_eq!(next[1], ("večer".to_string(), 1));
    }

    #[test]
    fn remove_deletes_word_and_its_bigrams() {
        let mut u = UserDict::new();
        u.learn("den", "dobrý", NOW);
        assert!(u.remove("den"));
        assert!(!u.remove("den"));
        assert!(u.next_words("dobrý").is_empty());
        assert_eq!(u.strength("den", NOW), 0.0);
    }

    #[test]
    fn forgetting_removes_stale_words_only() {
        let mut u = UserDict::new();
        u.insert_word("jednou", 1, NOW - 40 * DAY_MS);
        u.insert_word("obcas", 4, NOW - 200 * DAY_MS);
        u.insert_word("casto", 50, NOW - 200 * DAY_MS);
        u.insert_word("davno", 50, NOW - 400 * DAY_MS);
        u.insert_word("cerstve", 1, NOW - DAY_MS);
        let mut gone = u.forget_stale(NOW);
        gone.sort();
        assert_eq!(gone, vec!["davno", "jednou", "obcas"]);
        assert_eq!(u.len(), 2);
    }

    #[test]
    fn export_import_roundtrip() {
        let mut u = UserDict::new();
        u.learn("Novák", "pan", NOW);
        u.learn("Novák", "pan", NOW);
        let json = serde_json::to_string(&u.export()).unwrap();
        let mut other = UserDict::new();
        let n = other.import(&serde_json::from_str(&json).unwrap());
        assert_eq!(n, 1);
        assert_eq!(other.get("Novák").unwrap().count, 2);
        assert_eq!(other.next_words("pan")[0].0, "Novák");
        // opakovany import cisla nenafoukne
        other.import(&serde_json::from_str(&json).unwrap());
        assert_eq!(other.get("Novák").unwrap().count, 2);
    }

    #[test]
    fn trigrams_are_learned_and_exported() {
        let mut u = UserDict::new();
        assert_eq!(u.learn_trigram("Jak", "se", "máš"), Some(("jak se".into(), "máš".into(), 1)));
        u.learn_trigram("jak", "se", "máš");
        u.learn_trigram("jak", "se", "daří");
        assert!(u.learn_trigram("", "se", "máš").is_none());
        let next = u.next_after("JAK", "se");
        assert_eq!(next[0], ("máš".to_string(), 2));
        assert_eq!(next[1], ("daří".to_string(), 1));
        assert!(u.next_after("", "se").is_empty());
        let mut other = UserDict::new();
        other.import(&u.export());
        assert_eq!(other.next_after("jak", "se")[0].0, "máš");
    }

    #[test]
    fn corrections_and_rejections() {
        let mut u = UserDict::new();
        assert_eq!(u.note_correction("mas", "máš"), 1);
        assert_eq!(u.note_correction("mas", "máš"), 2);
        assert_eq!(u.note_correction("blbost", ""), 1);
        assert_eq!(u.note_correction("blbost", ""), 2);
        assert_eq!(u.rejections("blbost"), 2);
        assert_eq!(u.rejections("mas"), 0);
        assert_eq!(u.corrections_from("mas").collect::<Vec<_>>(), vec![("máš", 2)]);
        assert_eq!(u.corrections_from("blbost").count(), 0);
        assert_eq!(u.forgive("blbost"), Some(1));
        assert_eq!(u.forgive("blbost"), Some(0));
        assert_eq!(u.forgive("blbost"), None);
        assert_eq!(u.forgive("nikdy"), None);
    }

    #[test]
    fn corrections_list_and_merge() {
        let mut u = UserDict::new();
        u.note_correction("mas", "máš");
        u.note_correction("blbost", "");
        u.note_correction("blbost", "");
        assert_eq!(u.merge_correction("mas", "máš", 5), 5);
        assert_eq!(u.merge_correction("mas", "máš", 2), 5);
        assert_eq!(u.merge_correction("nove", "nové", 1), 1);
        assert_eq!(
            u.corrections_list(),
            vec![
                ("blbost".to_string(), String::new(), 2),
                ("mas".to_string(), "máš".to_string(), 5),
                ("nove".to_string(), "nové".to_string(), 1),
            ]
        );
        u.forgive("blbost");
        u.forgive("blbost");
        assert_eq!(u.corrections_list().len(), 2); // nulove pocty se nevypisuji
    }

    #[test]
    fn strength_falls_back_to_lowercase() {
        let mut u = UserDict::new();
        u.learn("ahoj", "", NOW);
        assert!(u.strength("Ahoj", NOW) > 0.0);
    }
}
