//! Historie schranky: pravidla (limit, expirace, detekce hesel). Data jsou v SQLite (storage.rs).

use serde::Serialize;

pub const MAX_ITEMS: usize = 30;
pub const MAX_TEXT_CHARS: usize = 4000;

#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct ClipItem {
    pub id: i64,
    pub text: String,
    pub created: i64,
    pub pinned: bool,
}

/// Heuristika: vypada text jako heslo nebo jednorazovy kod? Takovy text se neuklada.
/// - jedno "slovo" bez mezer, 8-64 znaku, alespon 3 ze 4 trid znaku (mala, velka, cislice, symboly)
/// - nebo ciste ciselny kod o 5-8 cislicich (OTP)
/// URL a e-maily se za heslo nepovazuji.
pub fn looks_like_password(text: &str) -> bool {
    let t = text.trim();
    let n = t.chars().count();
    if n == 0 || t.chars().any(char::is_whitespace) {
        return false;
    }
    if (5..=8).contains(&n) && t.chars().all(|c| c.is_ascii_digit()) {
        return true;
    }
    if !(8..=64).contains(&n) {
        return false;
    }
    let lower = t.to_lowercase();
    if lower.contains("://") || lower.starts_with("www.") {
        return false;
    }
    if let Some((user, domain)) = t.split_once('@') {
        if !user.is_empty() && domain.contains('.') && !domain.contains('@') {
            return false;
        }
    }
    let classes = [
        t.chars().any(|c| c.is_lowercase()),
        t.chars().any(|c| c.is_uppercase()),
        t.chars().any(|c| c.is_ascii_digit()),
        t.chars().any(|c| !c.is_alphanumeric()),
    ];
    classes.iter().filter(|&&c| c).count() >= 3
}

/// Smi se text ulozit do historie?
pub fn is_storable(text: &str) -> bool {
    !text.trim().is_empty() && text.chars().count() <= MAX_TEXT_CHARS && !looks_like_password(text)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn detects_passwords_and_otp() {
        assert!(looks_like_password("Tr0ub4dor&3"));
        assert!(looks_like_password("xK9#mP2$vL"));
        assert!(looks_like_password("Heslo12345"));
        assert!(looks_like_password("482913"));
    }

    #[test]
    fn normal_text_is_not_password() {
        assert!(!looks_like_password("Ahoj, jak se máš?"));
        assert!(!looks_like_password("dobrýden"));
        assert!(!looks_like_password("https://example.com/Path?x=1"));
        assert!(!looks_like_password("Jan.Novak2@example.com"));
        assert!(!looks_like_password("2025"));
        assert!(!looks_like_password("123456789012"));
    }

    #[test]
    fn storable_rules() {
        assert!(is_storable("nějaký text"));
        assert!(!is_storable("   "));
        assert!(!is_storable("Tr0ub4dor&3"));
        assert!(!is_storable(&"x".repeat(MAX_TEXT_CHARS + 1)));
    }
}
