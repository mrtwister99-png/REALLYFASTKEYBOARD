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
    bigrams: HashMap<String, Vec<(String, u32)>>,
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

    pub fn insert_word(&mut self, word: &str, count: u32, last_used: i64) {
        self.words.insert(word.to_string(), UserWord { count, last_used });
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
        self.bigrams.remove(&word.to_lowercase());
        for list in self.bigrams.values_mut() {
            list.retain(|(w, _)| w != word);
        }
        self.bigrams.retain(|_, l| !l.is_empty());
        removed
    }

    pub fn clear(&mut self) {
        self.words.clear();
        self.bigrams.clear();
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
        Export { version: 1, words: self.list(), bigrams }
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
        n
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
    fn strength_falls_back_to_lowercase() {
        let mut u = UserDict::new();
        u.learn("ahoj", "", NOW);
        assert!(u.strength("Ahoj", NOW) > 0.0);
    }
}
