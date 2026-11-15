//! Hlasove povely pro diktovani: slovo nebo kratka fraze, ktera misto zapsani neco udela
//! (smaze slovo, vlozi novy radek, vlozi text, ukonci diktovani...). Povel se provede, az
//! kdyz uzivatel domluvi - rozpoznany text se tu rozdeli na obycejny text a povely.

use crate::charmap;
use serde::{Deserialize, Serialize};

pub const ACTIONS: [&str; 6] = ["delete_word", "delete_last", "newline", "enter", "stop", "text"];
pub const MAX_COMMANDS: usize = 40;
const MAX_PHRASE_CHARS: usize = 40;
const MAX_PHRASE_WORDS: usize = 4;
const MAX_TEXT_CHARS: usize = 200;

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct VoiceCommand {
    /// 0 = novy povel (id prideli databaze).
    pub id: i64,
    pub phrase: String,
    pub action: String,
    /// Vkladany text, jen pro akci "text".
    pub text: String,
}

/// Usek rozpoznaneho textu: `action` prazdna = obycejny text, jinak povel.
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct Segment {
    pub action: String,
    pub text: String,
}

impl VoiceCommand {
    /// Ocisti frazi a text. `None`, kdyz je fraze neplatna, akce neznama
    /// nebo akce "text" nema zadny text.
    pub fn normalized(mut self) -> Option<VoiceCommand> {
        let words: Vec<&str> = self.phrase.split_whitespace().collect();
        let phrase = words.join(" ");
        if words.is_empty() || words.len() > MAX_PHRASE_WORDS || phrase.chars().count() > MAX_PHRASE_CHARS {
            return None;
        }
        if !ACTIONS.contains(&self.action.as_str()) {
            return None;
        }
        self.phrase = phrase;
        if self.action == "text" {
            if self.text.trim().is_empty() {
                return None;
            }
            self.text = self.text.chars().take(MAX_TEXT_CHARS).collect();
        } else {
            self.text.clear();
        }
        Some(self)
    }
}

/// Slovo pro porovnani: mala pismena, bez diakritiky, bez interpunkce na krajich.
fn fold_token(t: &str) -> String {
    charmap::fold_str(t.trim_matches(|c: char| !c.is_alphanumeric()))
}

/// Rozdeli rozpoznany text na obycejne usece a povely. Pri shode se bere povel
/// s nejdelsi frazi; jen castecna shoda zustava textem.
pub fn split(commands: &[VoiceCommand], spoken: &str) -> Vec<Segment> {
    let tokens: Vec<&str> = spoken.split_whitespace().collect();
    let folded: Vec<String> = tokens.iter().map(|t| fold_token(t)).collect();
    let phrases: Vec<(Vec<String>, &VoiceCommand)> = commands
        .iter()
        .map(|c| (c.phrase.split_whitespace().map(fold_token).collect::<Vec<_>>(), c))
        .filter(|(p, _)| !p.is_empty() && p.iter().all(|w| !w.is_empty()))
        .collect();

    let mut out = Vec::new();
    let mut plain: Vec<&str> = Vec::new();
    let flush = |plain: &mut Vec<&str>, out: &mut Vec<Segment>| {
        if !plain.is_empty() {
            out.push(Segment { action: String::new(), text: plain.join(" ") });
            plain.clear();
        }
    };
    let mut i = 0;
    while i < tokens.len() {
        let best = phrases
            .iter()
            .filter(|(p, _)| i + p.len() <= tokens.len() && p.iter().zip(&folded[i..]).all(|(a, b)| a == b))
            .max_by_key(|(p, _)| p.len());
        match best {
            Some((p, c)) => {
                flush(&mut plain, &mut out);
                out.push(Segment { action: c.action.clone(), text: c.text.clone() });
                i += p.len();
            }
            None => {
                plain.push(tokens[i]);
                i += 1;
            }
        }
    }
    flush(&mut plain, &mut out);
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn cmd(phrase: &str, action: &str, text: &str) -> VoiceCommand {
        VoiceCommand { id: 0, phrase: phrase.into(), action: action.into(), text: text.into() }
    }

    fn seg(action: &str, text: &str) -> Segment {
        Segment { action: action.into(), text: text.into() }
    }

    #[test]
    fn no_commands_gives_one_plain_segment() {
        assert_eq!(split(&[], "ahoj  světe"), vec![seg("", "ahoj světe")]);
        assert!(split(&[], "   ").is_empty());
        assert!(split(&[cmd("smazat", "delete_word", "")], "").is_empty());
    }

    #[test]
    fn command_in_the_middle_splits_text() {
        let c = [cmd("nový řádek", "newline", ""), cmd("tečka", "text", ".")];
        assert_eq!(
            split(&c, "dobrý den nový řádek jak se máš"),
            vec![seg("", "dobrý den"), seg("newline", ""), seg("", "jak se máš")]
        );
        assert_eq!(split(&c, "konec tečka"), vec![seg("", "konec"), seg("text", ".")]);
        assert_eq!(split(&c, "tečka tečka"), vec![seg("text", "."), seg("text", ".")]);
    }

    #[test]
    fn matching_ignores_case_punctuation_and_diacritics() {
        let c = [cmd("smazat", "delete_word", ""), cmd("Nový řádek", "newline", "")];
        assert_eq!(split(&c, "Ahoj, Smazat."), vec![seg("", "Ahoj,"), seg("delete_word", "")]);
        assert_eq!(split(&c, "ahoj novy radek"), vec![seg("", "ahoj"), seg("newline", "")]);
        assert_eq!(split(&c, "NOVÝ ŘÁDEK!"), vec![seg("newline", "")]);
    }

    #[test]
    fn longest_phrase_wins() {
        let c = [cmd("smazat", "delete_word", ""), cmd("smazat vse", "delete_last", "")];
        assert_eq!(split(&c, "a smazat vše"), vec![seg("", "a"), seg("delete_last", "")]);
        assert_eq!(split(&c, "smazat to"), vec![seg("delete_word", ""), seg("", "to")]);
    }

    #[test]
    fn partial_phrase_stays_text() {
        let c = [cmd("nový řádek", "newline", "")];
        assert_eq!(split(&c, "nový den"), vec![seg("", "nový den")]);
        assert_eq!(split(&c, "řekl nový"), vec![seg("", "řekl nový")]);
    }

    #[test]
    fn normalization_rules() {
        let ok = cmd("  nový   řádek ", "newline", "zbytek").normalized().unwrap();
        assert_eq!((ok.phrase.as_str(), ok.text.as_str()), ("nový řádek", ""));
        assert!(cmd("   ", "newline", "").normalized().is_none());
        assert!(cmd("jedna dva tri ctyri pet", "enter", "").normalized().is_none());
        assert!(cmd(&"a".repeat(41), "enter", "").normalized().is_none());
        assert!(cmd("x", "neznama", "").normalized().is_none());
        assert!(cmd("x", "text", "  ").normalized().is_none());
        let t = cmd("x", "text", &"a".repeat(500)).normalized().unwrap();
        assert_eq!(t.text.chars().count(), MAX_TEXT_CHARS);
        for a in ACTIONS {
            assert!(cmd("x", a, "t").normalized().is_some());
        }
    }
}
