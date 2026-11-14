//! Jednobajtove kodovani slov ve slovniku.
//!
//! Kazdy znak abecedy = 1 bajt, takze jeden prechod v FST odpovida jednomu pismenu
//! (swipe decoder i fuzzy hledani tak nemusi resit vicebajtove UTF-8).
//! Abeceda musi odpovidat `ALPHABET` ve scripts/download-dictionary.py.

/// Znaky s diakritikou; bajt = 0x80 + index.
pub const EXTRA: [char; 24] = [
    'á', 'č', 'ď', 'é', 'ě', 'í', 'ň', 'ó', 'ř', 'š', 'ť', 'ú', 'ů', 'ý', 'ž', 'ä', 'ö', 'ü', 'ô',
    'ľ', 'ĺ', 'ŕ', 'ë', 'ñ',
];
/// Zakladni pismeno (bez diakritiky) pro kazdy znak z `EXTRA`.
const EXTRA_FOLD: &[u8; 24] = b"acdeeinorstuuyzaouollren";

pub const MAX_WORD: usize = 30;
pub const APOSTROPHE: u8 = b'\'';

#[inline]
pub fn encode_char(c: char) -> Option<u8> {
    if c.is_ascii_lowercase() || c == '\'' {
        return Some(c as u8);
    }
    EXTRA.iter().position(|&e| e == c).map(|i| 0x80 + i as u8)
}

#[inline]
pub fn decode_byte(b: u8) -> char {
    if b < 0x80 {
        b as char
    } else {
        EXTRA.get((b - 0x80) as usize).copied().unwrap_or('?')
    }
}

/// Odstrani diakritiku z kodovaneho bajtu.
#[inline]
pub fn fold_byte(b: u8) -> u8 {
    if b < 0x80 {
        b
    } else {
        EXTRA_FOLD.get((b - 0x80) as usize).copied().unwrap_or(b)
    }
}

/// Zakoduje slovo (prevede na mala pismena). `None`, pokud obsahuje znak mimo abecedu
/// nebo je delsi nez `MAX_WORD`.
pub fn encode_word(s: &str) -> Option<Vec<u8>> {
    let mut out = Vec::with_capacity(s.len());
    for c in s.chars() {
        let mut lower = c.to_lowercase();
        let lc = lower.next()?;
        if lower.next().is_some() {
            return None;
        }
        out.push(encode_char(lc)?);
        if out.len() > MAX_WORD {
            return None;
        }
    }
    if out.is_empty() {
        None
    } else {
        Some(out)
    }
}

pub fn decode_word(bytes: &[u8]) -> String {
    bytes.iter().map(|&b| decode_byte(b)).collect()
}

pub fn fold_bytes(bytes: &[u8]) -> Vec<u8> {
    bytes.iter().map(|&b| fold_byte(b)).collect()
}

/// Mala pismena bez diakritiky pro libovolny text (napr. hledani emoji).
pub fn fold_str(s: &str) -> String {
    s.chars()
        .flat_map(|c| c.to_lowercase())
        .map(|c| match encode_char(c) {
            Some(b) => fold_byte(b) as char,
            None => c,
        })
        .collect()
}

/// Vsechny kodovane bajty, ktere po odstraneni diakritiky davaji `base`
/// (vcetne `base` samotneho na prvnim miste).
pub fn variants(base: u8) -> impl Iterator<Item = u8> {
    std::iter::once(base).chain(
        EXTRA_FOLD
            .iter()
            .enumerate()
            .filter(move |(_, &f)| f == base && base >= b'a')
            .map(|(i, _)| 0x80 + i as u8),
    )
}

/// Prvni pismeno velke.
pub fn capitalize(s: &str) -> String {
    let mut chars = s.chars();
    match chars.next() {
        Some(c) => c.to_uppercase().chain(chars).collect(),
        None => String::new(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn roundtrip_czech_word() {
        let enc = encode_word("Příliš").unwrap();
        assert_eq!(enc.len(), 6);
        assert_eq!(decode_word(&enc), "příliš");
    }

    #[test]
    fn rejects_foreign_and_digits() {
        assert!(encode_word("abc1").is_none());
        assert!(encode_word("").is_none());
        assert!(encode_word("日本").is_none());
    }

    #[test]
    fn folding_strips_diacritics() {
        let enc = encode_word("žluťoučký").unwrap();
        assert_eq!(decode_word(&fold_bytes(&enc)), "zlutoucky");
        assert_eq!(fold_str("Kůň ÚPĚL"), "kun upel");
    }

    #[test]
    fn variants_of_e() {
        let v: Vec<char> = variants(b'e').map(decode_byte).collect();
        assert_eq!(v, vec!['e', 'é', 'ě', 'ë']);
        assert_eq!(variants(b'b').count(), 1);
    }
}
