//! Gesta a drobna editacni logika: smazani slova, posun kurzoru, Caps, schovani klavesnice,
//! zmena vysky (pinch), stav Shiftu, automaticka velka pismena, dvojita mezera -> tecka.
//! Delky vracene pro InputConnection jsou v UTF-16 jednotkach (tak pocita Android).

use crate::layout::{KIND_BACKSPACE, KIND_SPACE};
use crate::settings::Settings;

pub const ACTION_NONE: i32 = 0;
pub const ACTION_DELETE_WORD: i32 = 1;
pub const ACTION_CAPS_ON: i32 = 2;
pub const ACTION_HIDE_KEYBOARD: i32 = 3;
pub const ACTION_CURSOR_WORD_LEFT: i32 = 4;
pub const ACTION_CURSOR_WORD_RIGHT: i32 = 5;

pub const SHIFT_OFF: u8 = 0;
pub const SHIFT_ONCE: u8 = 1;
pub const SHIFT_CAPS: u8 = 2;
/// Okno pro double tap na Shift.
pub const DOUBLE_TAP_MS: i64 = 400;

/// Klasifikace dokonceneho tahu. `start_kind` = druh klavesy, na ktere tah zacal;
/// `dx`, `dy` v pixelech, `dt_ms` doba tahu, `key_w` / `key_h` rozmery klavesy.
pub fn classify(start_kind: u8, dx: f32, dy: f32, dt_ms: f32, key_w: f32, key_h: f32, s: &Settings) -> i32 {
    if !(dx.is_finite() && dy.is_finite() && key_w > 0.0 && key_h > 0.0) {
        return ACTION_NONE;
    }
    let (ax, ay) = (dx.abs(), dy.abs());
    if start_kind == KIND_BACKSPACE {
        if s.gesture_delete_word && dx < -0.8 * key_w && ay < ax {
            return ACTION_DELETE_WORD;
        }
        return ACTION_NONE;
    }
    if start_kind == KIND_SPACE {
        // Rychly svih po mezerniku = skok o slovo (pomaly tah resi `cursor_steps`).
        if s.gesture_space_cursor && dt_ms < 220.0 && ax > 1.5 * key_w && ay < ax {
            return if dx < 0.0 { ACTION_CURSOR_WORD_LEFT } else { ACTION_CURSOR_WORD_RIGHT };
        }
        return ACTION_NONE;
    }
    // Svisly svih pres klavesnici: rychly, dlouhy a temer kolmy (aby se nepletl se swipe psanim).
    if dt_ms < 260.0 && ay > 2.5 * key_h && ay > 4.0 * ax {
        if dy < 0.0 && s.gesture_swipe_up_caps {
            return ACTION_CAPS_ON;
        }
        if dy > 0.0 && s.gesture_swipe_down_hide {
            return ACTION_HIDE_KEYBOARD;
        }
    }
    ACTION_NONE
}

/// Pomaly tah po mezerniku: pocet znaku, o ktere se ma kurzor posunout
/// (zaporne = doleva). Jeden krok = 40 % sirky klavesy.
pub fn cursor_steps(dx: f32, key_w: f32) -> i32 {
    if !dx.is_finite() || key_w <= 0.0 {
        return 0;
    }
    (dx / (0.4 * key_w)).trunc().clamp(-200.0, 200.0) as i32
}

fn utf16_len(s: &str) -> usize {
    s.chars().map(char::len_utf16).sum()
}

fn is_word_char(c: char) -> bool {
    c.is_alphanumeric() || c == '\''
}

/// Kolik UTF-16 jednotek pred kurzorem smazat pri "smazat slovo":
/// koncove mezery + cele slovo (nebo shluk interpunkce).
pub fn delete_word_len(before: &str) -> usize {
    let trimmed = before.trim_end_matches(char::is_whitespace);
    let spaces = &before[trimmed.len()..];
    let word_start = match trimmed.chars().next_back() {
        Some(c) if is_word_char(c) => trimmed.trim_end_matches(is_word_char).len(),
        Some(_) => trimmed.trim_end_matches(|c: char| !is_word_char(c) && !c.is_whitespace()).len(),
        None => 0,
    };
    utf16_len(&trimmed[word_start..]) + utf16_len(spaces)
}

/// Posun kurzoru o slovo. `text` je text pred kurzorem (`forward = false`)
/// nebo za kurzorem (`forward = true`). Vraci pocet UTF-16 jednotek.
pub fn word_jump_len(text: &str, forward: bool) -> usize {
    if !forward {
        return delete_word_len(text);
    }
    let rest = text.trim_start_matches(|c: char| !is_word_char(c));
    let skipped = &text[..text.len() - rest.len()];
    let after_word = rest.trim_start_matches(is_word_char);
    utf16_len(skipped) + utf16_len(&rest[..rest.len() - after_word.len()])
}

/// Novy stav Shiftu po tapnuti. `since_last_tap_ms` = cas od predchoziho tapnuti na Shift.
pub fn shift_tap(state: u8, since_last_tap_ms: i64) -> u8 {
    match state {
        SHIFT_OFF => SHIFT_ONCE,
        SHIFT_ONCE if (0..=DOUBLE_TAP_MS).contains(&since_last_tap_ms) => SHIFT_CAPS,
        _ => SHIFT_OFF,
    }
}

/// Ma byt dalsi znak velky? (zacatek textu nebo po konci vety)
pub fn should_capitalize(before: &str) -> bool {
    let t = before.trim_end_matches([' ', '\t']);
    match t.chars().next_back() {
        None => true,
        Some('\n') => true,
        Some('.') | Some('!') | Some('?') | Some('…') => t.len() < before.len(),
        _ => false,
    }
}

/// Dvojita mezera -> tecka: plati, kdyz text pred kurzorem konci "<pismeno/cislice><mezera>".
pub fn double_space_period(before: &str) -> bool {
    let mut it = before.chars().rev();
    matches!((it.next(), it.next()), (Some(' '), Some(c)) if c.is_alphanumeric() || matches!(c, ')' | ']' | '"' | '%'))
}

/// Pinch: zmeni vysku klavesnice (procenta obrazovky i vysku klavesy) v povolenych mezich.
pub fn apply_pinch(s: &mut Settings, scale: f32) {
    if !s.gesture_pinch_resize || !scale.is_finite() || scale <= 0.0 {
        return;
    }
    let scale = scale.clamp(0.5, 2.0);
    s.keyboard_height_percent = ((s.keyboard_height_percent as f32 * scale).round() as u32).clamp(30, 50);
    s.key_height_dp = ((s.key_height_dp as f32 * scale).round() as u32).clamp(48, 72);
}

/// Posledni slovo pred kurzorem (pro bigramovou predikci). Prazdne, kdyz pred kurzorem
/// neni slovo nebo je mezi nim a kurzorem konec vety.
pub fn last_word(before: &str) -> &str {
    let t = before.trim_end_matches([' ', '\t']);
    let start = t.trim_end_matches(is_word_char).len();
    &t[start..]
}

/// Posledni dve slova pred kurzorem (predposledni, posledni) pro trigramovou predikci.
/// Konec vety mezi slovy kontext prerusi.
pub fn last_two_words(before: &str) -> (&str, &str) {
    let t = before.trim_end_matches([' ', '\t']);
    let start = t.trim_end_matches(is_word_char).len();
    let prev = &t[start..];
    if prev.is_empty() {
        return ("", "");
    }
    (last_word(&t[..start]), prev)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::layout::KIND_CHAR;

    fn s() -> Settings {
        Settings::default()
    }

    #[test]
    fn backspace_swipe_left_deletes_word() {
        assert_eq!(classify(KIND_BACKSPACE, -150.0, 10.0, 150.0, 140.0, 150.0, &s()), ACTION_DELETE_WORD);
        assert_eq!(classify(KIND_BACKSPACE, -50.0, 0.0, 150.0, 140.0, 150.0, &s()), ACTION_NONE);
        assert_eq!(classify(KIND_BACKSPACE, 150.0, 0.0, 150.0, 140.0, 150.0, &s()), ACTION_NONE);
    }

    #[test]
    fn space_flick_jumps_by_word_and_slow_drag_by_chars() {
        assert_eq!(classify(KIND_SPACE, -300.0, 5.0, 120.0, 140.0, 150.0, &s()), ACTION_CURSOR_WORD_LEFT);
        assert_eq!(classify(KIND_SPACE, 300.0, 5.0, 120.0, 140.0, 150.0, &s()), ACTION_CURSOR_WORD_RIGHT);
        assert_eq!(classify(KIND_SPACE, 300.0, 5.0, 900.0, 140.0, 150.0, &s()), ACTION_NONE);
        assert_eq!(cursor_steps(140.0, 140.0), 2);
        assert_eq!(cursor_steps(-60.0, 140.0), -1);
        assert_eq!(cursor_steps(20.0, 140.0), 0);
        assert_eq!(cursor_steps(f32::NAN, 140.0), 0);
    }

    #[test]
    fn vertical_flicks_caps_and_hide() {
        assert_eq!(classify(KIND_CHAR, 10.0, -450.0, 150.0, 140.0, 150.0, &s()), ACTION_CAPS_ON);
        assert_eq!(classify(KIND_CHAR, -10.0, 450.0, 150.0, 140.0, 150.0, &s()), ACTION_HIDE_KEYBOARD);
        // sikmy nebo pomaly tah je swipe psani, ne gesto
        assert_eq!(classify(KIND_CHAR, 200.0, -450.0, 150.0, 140.0, 150.0, &s()), ACTION_NONE);
        assert_eq!(classify(KIND_CHAR, 0.0, -450.0, 600.0, 140.0, 150.0, &s()), ACTION_NONE);
    }

    #[test]
    fn gestures_can_be_disabled() {
        let mut off = s();
        off.gesture_delete_word = false;
        off.gesture_space_cursor = false;
        off.gesture_swipe_up_caps = false;
        off.gesture_swipe_down_hide = false;
        off.gesture_pinch_resize = false;
        assert_eq!(classify(KIND_BACKSPACE, -150.0, 0.0, 150.0, 140.0, 150.0, &off), ACTION_NONE);
        assert_eq!(classify(KIND_SPACE, -300.0, 0.0, 120.0, 140.0, 150.0, &off), ACTION_NONE);
        assert_eq!(classify(KIND_CHAR, 0.0, -450.0, 150.0, 140.0, 150.0, &off), ACTION_NONE);
        assert_eq!(classify(KIND_CHAR, 0.0, 450.0, 150.0, 140.0, 150.0, &off), ACTION_NONE);
        apply_pinch(&mut off, 1.3);
        assert_eq!(off.keyboard_height_percent, 38);
    }

    #[test]
    fn delete_word_counts_utf16_units() {
        assert_eq!(delete_word_len("ahoj světe"), 5);
        assert_eq!(delete_word_len("ahoj světe  "), 7);
        assert_eq!(delete_word_len("ahoj"), 4);
        assert_eq!(delete_word_len(""), 0);
        assert_eq!(delete_word_len("konec..."), 3);
        assert_eq!(delete_word_len("smajlík 😀😀"), 4); // 2 emoji = 4 UTF-16 jednotky
    }

    #[test]
    fn word_jump_forward() {
        assert_eq!(word_jump_len(" světe ahoj", true), 6);
        assert_eq!(word_jump_len("světe", true), 5);
        assert_eq!(word_jump_len("", true), 0);
        assert_eq!(word_jump_len("ahoj světe", false), 5);
    }

    #[test]
    fn shift_state_machine() {
        assert_eq!(shift_tap(SHIFT_OFF, 10_000), SHIFT_ONCE);
        assert_eq!(shift_tap(SHIFT_ONCE, 200), SHIFT_CAPS);
        assert_eq!(shift_tap(SHIFT_ONCE, 900), SHIFT_OFF);
        assert_eq!(shift_tap(SHIFT_CAPS, 100), SHIFT_OFF);
    }

    #[test]
    fn auto_capitalization_rules() {
        assert!(should_capitalize(""));
        assert!(should_capitalize("Ahoj. "));
        assert!(should_capitalize("Opravdu?  "));
        assert!(should_capitalize("řádek\n"));
        assert!(!should_capitalize("Ahoj"));
        assert!(!should_capitalize("Ahoj "));
        assert!(!should_capitalize("např."));
        assert!(!should_capitalize("3.14"));
    }

    #[test]
    fn double_space_rule() {
        assert!(double_space_period("Ahoj "));
        assert!(!double_space_period("Ahoj.  "));
        assert!(!double_space_period("Ahoj. "));
        assert!(!double_space_period(" "));
        assert!(!double_space_period("Ahoj"));
    }

    #[test]
    fn pinch_scales_within_limits() {
        let mut st = s();
        apply_pinch(&mut st, 1.2);
        assert_eq!(st.keyboard_height_percent, 46);
        assert_eq!(st.key_height_dp, 67);
        apply_pinch(&mut st, 5.0);
        assert_eq!((st.keyboard_height_percent, st.key_height_dp), (50, 72));
        apply_pinch(&mut st, 0.1);
        assert_eq!((st.keyboard_height_percent, st.key_height_dp), (30, 48));
    }

    #[test]
    fn last_two_words_extraction() {
        assert_eq!(last_two_words("jak se máš "), ("se", "máš"));
        assert_eq!(last_two_words("jak se"), ("jak", "se"));
        assert_eq!(last_two_words("ahoj"), ("", "ahoj"));
        assert_eq!(last_two_words("Konec. Dobrý"), ("", "Dobrý"));
        assert_eq!(last_two_words("Konec. "), ("", ""));
        assert_eq!(last_two_words(""), ("", ""));
    }

    #[test]
    fn last_word_extraction() {
        assert_eq!(last_word("dobrý den "), "den");
        assert_eq!(last_word("dobrý"), "dobrý");
        assert_eq!(last_word("Konec. "), "");
        assert_eq!(last_word(""), "");
    }
}
