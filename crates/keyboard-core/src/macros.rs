//! Makra: uzivatelske textove bloky. Vkladaji se tlacitkem maker na klavesnici, nebo
//! zkratkou - kdyz uzivatel napise zkratku (napr. "mfg") a mezeru, nahradi se textem makra.

use serde::{Deserialize, Serialize};

pub const MAX_MACROS: usize = 40;
pub const MAX_NAME_CHARS: usize = 40;
pub const MAX_TEXT_CHARS: usize = 2000;
pub const MAX_SHORTCUT_CHARS: usize = 20;

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct Macro {
    /// 0 = nove makro (id prideli databaze).
    pub id: i64,
    pub name: String,
    pub text: String,
    /// Volitelna zkratka; prazdna = makro jde vlozit jen tlacitkem.
    pub shortcut: String,
}

fn clip(s: &str, max: usize) -> String {
    s.chars().take(max).collect()
}

impl Macro {
    /// Orizne delky a doplni nazev. `None`, kdyz makro nema zadny text.
    pub fn normalized(mut self) -> Option<Macro> {
        if self.text.trim().is_empty() {
            return None;
        }
        self.text = clip(&self.text, MAX_TEXT_CHARS);
        self.shortcut = clip(self.shortcut.trim(), MAX_SHORTCUT_CHARS);
        if self.shortcut.chars().any(char::is_whitespace) {
            self.shortcut.clear(); // zkratka musi byt jedno "slovo"
        }
        self.name = clip(self.name.trim(), MAX_NAME_CHARS);
        if self.name.is_empty() {
            self.name = clip(self.text.trim().lines().next().unwrap_or(""), 24);
        }
        Some(self)
    }
}

/// Text makra pro napsanou zkratku (bez ohledu na velikost pismen); `None`, kdyz zadna nesedi.
pub fn expand<'a>(macros: &'a [Macro], token: &str) -> Option<&'a str> {
    if token.is_empty() {
        return None;
    }
    let t = token.to_lowercase();
    macros.iter().find(|m| !m.shortcut.is_empty() && m.shortcut.to_lowercase() == t).map(|m| m.text.as_str())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn list() -> Vec<Macro> {
        vec![
            Macro { id: 1, name: "Pozdrav".into(), text: "S pozdravem".into(), shortcut: "mfg".into() },
            Macro { id: 2, name: "Mail".into(), text: "jan@example.com".into(), shortcut: "@@".into() },
            Macro { id: 3, name: "Adresa".into(), text: "Dlouhá 1, Praha".into(), shortcut: String::new() },
        ]
    }

    #[test]
    fn shortcut_expands_case_insensitively() {
        let m = list();
        assert_eq!(expand(&m, "mfg"), Some("S pozdravem"));
        assert_eq!(expand(&m, "MFG"), Some("S pozdravem"));
        assert_eq!(expand(&m, "@@"), Some("jan@example.com"));
        assert_eq!(expand(&m, "mf"), None);
        assert_eq!(expand(&m, ""), None); // makro bez zkratky se psanim nespusti
    }

    #[test]
    fn normalization_rules() {
        assert!(Macro { text: "   ".into(), ..Default::default() }.normalized().is_none());
        let m = Macro { text: "Dobrý den,\nposílám…".into(), shortcut: " dd ".into(), ..Default::default() }.normalized().unwrap();
        assert_eq!(m.name, "Dobrý den,");
        assert_eq!(m.shortcut, "dd");
        let m = Macro { text: "x".into(), shortcut: "dve slova".into(), ..Default::default() }.normalized().unwrap();
        assert_eq!(m.shortcut, "");
        let long = Macro { text: "a".repeat(5000), name: "n".repeat(100), ..Default::default() }.normalized().unwrap();
        assert_eq!(long.text.chars().count(), MAX_TEXT_CHARS);
        assert_eq!(long.name.chars().count(), MAX_NAME_CHARS);
    }
}
