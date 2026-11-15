//! Rozlozeni klaves: CZ QWERTZ, EN QWERTY, cisla a symboly.
//! Rust pocita geometrii (v pixelech), Kotlin ji jen vykresli. Stejnou geometrii
//! pouziva swipe decoder, takze se vykresleni a dekodovani nemohou rozejit.

use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

pub const KIND_CHAR: u8 = 0;
pub const KIND_SHIFT: u8 = 1;
pub const KIND_BACKSPACE: u8 = 2;
pub const KIND_MODE: u8 = 3;
pub const KIND_ENTER: u8 = 4;
pub const KIND_SPACE: u8 = 5;
pub const KIND_GLOBE: u8 = 6;
pub const KIND_EMOJI: u8 = 7;
pub const KIND_MACRO: u8 = 8;

pub const CODE_SHIFT: i32 = -1;
pub const CODE_BACKSPACE: i32 = -2;
pub const CODE_SYMBOLS: i32 = -3;
pub const CODE_LETTERS: i32 = -4;
pub const CODE_SYMBOLS2: i32 = -5;
pub const CODE_ENTER: i32 = -6;
pub const CODE_GLOBE: i32 = -7;
pub const CODE_EMOJI: i32 = -8;
pub const CODE_MACRO: i32 = -9;
pub const CODE_SPACE: i32 = 32;

pub const MODE_LETTERS: u8 = 0;
pub const MODE_SYMBOLS: u8 = 1;
pub const MODE_SYMBOLS2: u8 = 2;

/// Pocet sloupcu (jednotek sirky) v radku.
const UNITS: f32 = 10.0;

#[derive(Debug, Clone, Serialize, PartialEq)]
pub struct Key {
    /// Dotykova oblast (hitbox) - pokryva celou bunku bez mezer.
    pub x: f32,
    pub y: f32,
    pub w: f32,
    pub h: f32,
    /// Vodorovna poloha a sirka vykreslene klavesy (krajni klavesy maji hitbox az k okraji).
    pub vx: f32,
    pub vw: f32,
    pub code: i32,
    /// Puvodni kod pozice (shodny s `code`, dokud klavesa neni premapovana).
    pub src: i32,
    pub kind: u8,
    pub label: String,
    /// Varianty pro dlouhy stisk (kazdy znak = jedna varianta).
    pub alts: String,
    /// Popis pro TalkBack.
    pub desc: String,
}

#[derive(Debug, Clone)]
pub struct Layout {
    pub keys: Vec<Key>,
    pub width: f32,
    pub height: f32,
    pub rows: usize,
    pub unit_w: f32,
    pub row_h: f32,
    pub mode: u8,
    /// Prvni radek je rada cislic nad pismeny (jen v rezimu pismen se zapnutou radou).
    pub number_row: bool,
}

/// Stredy pismen a-z v normalizovanych jednotkach (1 = sirka klavesy / vyska radku).
#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
pub struct KeyGeom {
    pub centers: [(f32, f32); 26],
    pub present: [bool; 26],
    pub unit_w: f32,
    pub row_h: f32,
}

struct Def {
    units: f32,
    code: i32,
    kind: u8,
    label: String,
    alts: String,
    desc: String,
}

fn ch(c: char, alts: &str) -> Def {
    Def { units: 1.0, code: c as i32, kind: KIND_CHAR, label: c.to_string(), alts: alts.to_string(), desc: describe(c) }
}

fn special(units: f32, code: i32, kind: u8, label: &str, desc: &str) -> Def {
    Def { units, code, kind, label: label.to_string(), alts: String::new(), desc: desc.to_string() }
}

fn describe(c: char) -> String {
    match c {
        '.' => "tečka".into(),
        ',' => "čárka".into(),
        '?' => "otazník".into(),
        '!' => "vykřičník".into(),
        ':' => "dvojtečka".into(),
        ';' => "středník".into(),
        '-' => "pomlčka".into(),
        '_' => "podtržítko".into(),
        '(' => "levá závorka".into(),
        ')' => "pravá závorka".into(),
        '/' => "lomítko".into(),
        '@' => "zavináč".into(),
        '#' => "mřížka".into(),
        '&' => "ampersand".into(),
        '+' => "plus".into(),
        '*' => "hvězdička".into(),
        '"' => "uvozovky".into(),
        '\'' => "apostrof".into(),
        '=' => "rovná se".into(),
        '%' => "procento".into(),
        _ => c.to_string(),
    }
}

/// Varianty s diakritikou pro dlouhy stisk pismene.
pub fn letter_alts(c: char) -> &'static str {
    match c {
        'a' => "áä",
        'c' => "čć",
        'd' => "ď",
        'e' => "éěë",
        'i' => "í",
        'l' => "ľĺł",
        'n' => "ňñ",
        'o' => "óöô",
        'r' => "řŕ",
        's' => "šß",
        't' => "ť",
        'u' => "úůü",
        'y' => "ý",
        'z' => "ž",
        _ => "",
    }
}

fn letter_row(letters: &str, digits: Option<&str>) -> Vec<Def> {
    letters
        .chars()
        .enumerate()
        .map(|(i, c)| {
            let mut alts = String::new();
            if let Some(d) = digits.and_then(|d| d.chars().nth(i)) {
                alts.push(d);
            }
            alts.push_str(letter_alts(c));
            ch(c, &alts)
        })
        .collect()
}

fn char_row(chars: &str) -> Vec<Def> {
    chars.chars().map(|c| ch(c, "")).collect()
}

/// Kde je ve spodni rade tlacitko emoji / maker.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum KeyPlace {
    Left,
    Right,
    Hidden,
}

impl KeyPlace {
    /// Neznama hodnota = vychozi (vlevo).
    pub fn parse(s: &str) -> KeyPlace {
        match s {
            "right" => KeyPlace::Right,
            "hidden" => KeyPlace::Hidden,
            _ => KeyPlace::Left,
        }
    }
}

/// Volitelna tlacitka spodni rady.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct BottomKeys {
    pub emoji: KeyPlace,
    pub macros: KeyPlace,
    /// Prohozene pozice klavesy ?123/ABC a Enter.
    pub swap_mode_enter: bool,
    /// Prohozene pozice klavesy ve slotu Shift a Backspace.
    pub swap_shift_delete: bool,
}

impl Default for BottomKeys {
    fn default() -> Self {
        BottomKeys {
            emoji: KeyPlace::Left,
            macros: KeyPlace::Left,
            swap_mode_enter: false,
            swap_shift_delete: false,
        }
    }
}

fn bottom_row(mode: u8, space_label: &str, bottom: BottomKeys) -> Vec<Def> {
    let switch = if mode == MODE_LETTERS {
        special(1.4, CODE_SYMBOLS, KIND_MODE, "?123", "Symboly a čísla")
    } else {
        special(1.4, CODE_LETTERS, KIND_MODE, "ABC", "Písmena")
    };
    let emoji = || special(1.0, CODE_EMOJI, KIND_EMOJI, "☺", "Emoji");
    // Jazyk se prepina podrzenim mezerniku; na miste globusu je tlacitko maker.
    let macros = || special(1.0, CODE_MACRO, KIND_MACRO, "✦", "Makra");
    // Skryta tlacitka uvolni misto mezerniku, rada ma porad 10 jednotek.
    let hidden = [bottom.emoji, bottom.macros].iter().filter(|p| **p == KeyPlace::Hidden).count();
    let space = special(3.2 + hidden as f32, CODE_SPACE, KIND_SPACE, space_label, "Mezerník");
    let mut row = vec![switch];
    if bottom.emoji == KeyPlace::Left {
        row.push(emoji());
    }
    row.push(ch(',', "'\"-_/@"));
    if bottom.macros == KeyPlace::Left {
        row.push(macros());
    }
    row.push(space);
    if bottom.emoji == KeyPlace::Right {
        row.push(emoji());
    }
    if bottom.macros == KeyPlace::Right {
        row.push(macros());
    }
    row.push(ch('.', "?!:;…"));
    row.push(special(1.4, CODE_ENTER, KIND_ENTER, "⏎", "Enter"));
    row
}

fn wrap_specials(left: Def, mut middle: Vec<Def>, right: Def) -> Vec<Def> {
    let mut row = vec![left];
    row.append(&mut middle);
    row.push(right);
    row
}

fn rows_for(qwertz: bool, mode: u8, number_row: bool, space_label: &str, bottom: BottomKeys) -> Vec<Vec<Def>> {
    let backspace = || special(1.5, CODE_BACKSPACE, KIND_BACKSPACE, "⌫", "Smazat");
    let mut rows = Vec::with_capacity(5);
    match mode {
        MODE_SYMBOLS => {
            rows.push(char_row("1234567890"));
            rows.push(char_row("@#&_-+()/*"));
            rows.push(wrap_specials(
                special(1.5, CODE_SYMBOLS2, KIND_MODE, "=\\<", "Další symboly"),
                char_row("\"':;!?€"),
                backspace(),
            ));
        }
        MODE_SYMBOLS2 => {
            rows.push(char_row("~`|•√π÷×§∆"));
            rows.push(char_row("£$€¥^°={}\\"));
            rows.push(wrap_specials(
                special(1.5, CODE_SYMBOLS, KIND_MODE, "?123", "Symboly a čísla"),
                char_row("%[]<>≠≈"),
                backspace(),
            ));
        }
        _ => {
            if number_row {
                rows.push(char_row("1234567890"));
            }
            let digits = if number_row { None } else { Some("1234567890") };
            let (top, bottom) = if qwertz { ("qwertzuiop", "yxcvbnm") } else { ("qwertyuiop", "zxcvbnm") };
            rows.push(letter_row(top, digits));
            rows.push(letter_row("asdfghjkl", None));
            rows.push(wrap_specials(
                special(1.5, CODE_SHIFT, KIND_SHIFT, "⇧", "Shift"),
                letter_row(bottom, None),
                backspace(),
            ));
        }
    }
    rows.push(bottom_row(mode, space_label, bottom));
    rows
}

/// Pocet radku klaves pro dany rezim.
pub fn row_count(mode: u8, number_row: bool) -> usize {
    if mode == MODE_LETTERS && !number_row {
        4
    } else if mode == MODE_LETTERS {
        5
    } else {
        4
    }
}

impl Layout {
    pub fn build(width: f32, height: f32, qwertz: bool, mode: u8, number_row: bool, space_label: &str) -> Layout {
        Layout::build_with(width, height, qwertz, mode, number_row, space_label, BottomKeys::default())
    }

    pub fn build_with(
        width: f32,
        height: f32,
        qwertz: bool,
        mode: u8,
        number_row: bool,
        space_label: &str,
        bottom: BottomKeys,
    ) -> Layout {
        let width = if width.is_finite() && width > 1.0 { width } else { 1080.0 };
        let height = if height.is_finite() && height > 1.0 { height } else { 800.0 };
        let rows = rows_for(qwertz, mode, number_row, space_label, bottom);
        let number_row = number_row && mode == MODE_LETTERS;
        let n_rows = rows.len();
        let unit_w = width / UNITS;
        let row_h = height / n_rows as f32;
        let mut keys = Vec::with_capacity(40);
        for (r, row) in rows.into_iter().enumerate() {
            let total: f32 = row.iter().map(|d| d.units).sum();
            let mut x = (UNITS - total) * 0.5 * unit_w;
            let last = row.len().saturating_sub(1);
            for (i, d) in row.into_iter().enumerate() {
                let vw = d.units * unit_w;
                let hx = if i == 0 { 0.0 } else { x };
                let hx_end = if i == last { width } else { x + vw };
                keys.push(Key {
                    x: hx,
                    y: r as f32 * row_h,
                    w: hx_end - hx,
                    h: row_h,
                    vx: x,
                    vw,
                    code: d.code,
                    src: d.code,
                    kind: d.kind,
                    label: d.label,
                    alts: d.alts,
                    desc: d.desc,
                });
                x += vw;
            }
        }
        let mut layout = Layout { keys, width, height, rows: n_rows, unit_w, row_h, mode, number_row };
        layout.apply_swaps(bottom.swap_mode_enter, bottom.swap_shift_delete);
        layout
    }

    /// Prohodi identitu dvou klaves (kod, druh, popisek...); geometrie slotu zustava.
    fn swap_identity(&mut self, a: usize, b: usize) {
        if a == b {
            return;
        }
        let (lo, hi) = if a < b { (a, b) } else { (b, a) };
        let (left, right) = self.keys.split_at_mut(hi);
        let (ka, kb) = (&mut left[lo], &mut right[0]);
        std::mem::swap(&mut ka.code, &mut kb.code);
        std::mem::swap(&mut ka.src, &mut kb.src);
        std::mem::swap(&mut ka.kind, &mut kb.kind);
        std::mem::swap(&mut ka.label, &mut kb.label);
        std::mem::swap(&mut ka.alts, &mut kb.alts);
        std::mem::swap(&mut ka.desc, &mut kb.desc);
    }

    /// Prohozeni pozic ?123/ABC <-> Enter (spodni rada) a Shift-slot <-> Backspace
    /// (predposledni rada; v symbolovych rezimech je v slotu Shift prepinac stranky symbolu).
    fn apply_swaps(&mut self, mode_enter: bool, shift_delete: bool) {
        let bottom_y = self.keys.iter().map(|k| k.y).fold(0.0f32, f32::max);
        if mode_enter {
            let m = self.keys.iter().position(|k| k.kind == KIND_MODE && k.y == bottom_y);
            let e = self.keys.iter().position(|k| k.kind == KIND_ENTER);
            if let (Some(m), Some(e)) = (m, e) {
                self.swap_identity(m, e);
            }
        }
        if shift_delete {
            let d = self.keys.iter().position(|k| k.kind == KIND_BACKSPACE);
            // Slot Shift = prvni klavesa v radku s Backspace.
            let sh = d.and_then(|d| {
                let y = self.keys[d].y;
                self.keys
                    .iter()
                    .enumerate()
                    .filter(|(_, k)| k.y == y)
                    .min_by(|a, b| a.1.x.total_cmp(&b.1.x))
                    .map(|(i, _)| i)
            });
            if let (Some(sh), Some(d)) = (sh, d) {
                self.swap_identity(sh, d);
            }
        }
    }

    /// Premapuje znakove klavesy podle nastaveni (puvodni znak pozice -> novy znak).
    /// Geometrie se nemeni, takze swipe decoder nasleduje nove rozlozeni pismen.
    pub fn apply_key_map(&mut self, map: &BTreeMap<String, String>) {
        if map.is_empty() {
            return;
        }
        for k in self.keys.iter_mut().filter(|k| k.kind == KIND_CHAR) {
            let Some(old) = char::from_u32(k.src as u32) else { continue };
            let Some(new) = map.get(old.to_string().as_str()).and_then(|v| v.chars().next()) else { continue };
            let digit = k.alts.chars().next().filter(char::is_ascii_digit);
            let alts: String = if new.is_ascii_lowercase() {
                digit.into_iter().chain(letter_alts(new).chars()).collect()
            } else if old.is_ascii_lowercase() {
                digit.into_iter().collect()
            } else {
                std::mem::take(&mut k.alts)
            };
            k.code = new as i32;
            k.label = new.to_string();
            k.desc = describe(new);
            k.alts = alts;
        }
    }

    /// Prepise varianty pro dlouhy stisk podle puvodniho znaku pozice (po `apply_key_map`).
    /// Prazdna hodnota = klavesa nema zadne varianty.
    pub fn apply_key_alts(&mut self, map: &BTreeMap<String, String>) {
        if map.is_empty() {
            return;
        }
        for k in self.keys.iter_mut().filter(|k| k.kind == KIND_CHAR) {
            let Some(old) = char::from_u32(k.src as u32) else { continue };
            if let Some(v) = map.get(old.to_string().as_str()) {
                k.alts = v.clone();
            }
        }
    }

    /// Klavesa pod bodem; mimo klavesy vraci nejblizsi.
    pub fn key_at(&self, x: f32, y: f32) -> Option<&Key> {
        let mut best: Option<(&Key, f32)> = None;
        for k in &self.keys {
            let dx = (k.x - x).max(0.0).max(x - (k.x + k.w));
            let dy = (k.y - y).max(0.0).max(y - (k.y + k.h));
            let d = dx * dx + dy * dy;
            if d == 0.0 {
                return Some(k);
            }
            if best.map_or(true, |(_, bd)| d < bd) {
                best = Some((k, d));
            }
        }
        best.map(|(k, _)| k)
    }

    pub fn key_for_code(&self, code: i32) -> Option<&Key> {
        self.keys.iter().find(|k| k.code == code)
    }

    /// Geometrie pismen pro swipe decoder.
    pub fn geom(&self) -> KeyGeom {
        let mut g = KeyGeom { centers: [(0.0, 0.0); 26], present: [false; 26], unit_w: self.unit_w, row_h: self.row_h };
        for k in &self.keys {
            if k.kind == KIND_CHAR && (b'a' as i32..=b'z' as i32).contains(&k.code) {
                let i = (k.code - b'a' as i32) as usize;
                g.centers[i] = ((k.vx + k.vw * 0.5) / self.unit_w, (k.y + k.h * 0.5) / self.row_h);
                g.present[i] = true;
            }
        }
        g
    }

    pub fn to_json(&self) -> String {
        #[derive(Serialize)]
        struct Out<'a> {
            width: f32,
            height: f32,
            rows: usize,
            mode: u8,
            number_row: bool,
            keys: &'a [Key],
        }
        serde_json::to_string(&Out {
            width: self.width,
            height: self.height,
            rows: self.rows,
            mode: self.mode,
            number_row: self.number_row,
            keys: &self.keys,
        })
            .unwrap_or_else(|_| "{\"keys\":[]}".to_string())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn letters(qwertz: bool) -> Layout {
        Layout::build(1440.0, 1000.0, qwertz, MODE_LETTERS, true, "Čeština")
    }

    #[test]
    fn qwertz_and_qwerty_swap_y_z() {
        let cz = letters(true);
        let en = letters(false);
        let row_of = |l: &Layout, c: char| (l.key_for_code(c as i32).unwrap().y / l.row_h).round() as usize;
        assert_eq!(row_of(&cz, 'z'), 1);
        assert_eq!(row_of(&cz, 'y'), 3);
        assert_eq!(row_of(&en, 'y'), 1);
        assert_eq!(row_of(&en, 'z'), 3);
    }

    #[test]
    fn all_letters_present_and_inside_bounds() {
        let l = letters(true);
        let g = l.geom();
        assert!(g.present.iter().all(|&p| p));
        for k in &l.keys {
            assert!(k.x >= 0.0 && k.x + k.w <= l.width + 0.01, "{}", k.label);
            assert!(k.y >= 0.0 && k.y + k.h <= l.height + 0.01);
            assert!(k.vx >= k.x - 0.01 && k.vx + k.vw <= k.x + k.w + 0.01);
        }
    }

    #[test]
    fn rows_fill_full_width_without_gaps() {
        for mode in [MODE_LETTERS, MODE_SYMBOLS, MODE_SYMBOLS2] {
            let l = Layout::build(1440.0, 1000.0, true, mode, true, "x");
            for r in 0..l.rows {
                let sum: f32 = l.keys.iter().filter(|k| (k.y / l.row_h).round() as usize == r).map(|k| k.w).sum();
                assert!((sum - 1440.0).abs() < 0.5, "mode {mode} row {r}: {sum}");
            }
        }
    }

    #[test]
    fn number_row_toggle_changes_rows_and_adds_digit_alts() {
        let with = letters(true);
        let without = Layout::build(1440.0, 1000.0, true, MODE_LETTERS, false, "x");
        assert_eq!(with.rows, 5);
        assert_eq!(without.rows, 4);
        assert_eq!(row_count(MODE_LETTERS, false), 4);
        assert!(without.key_for_code('e' as i32).unwrap().alts.starts_with('3'));
        assert_eq!(with.key_for_code('e' as i32).unwrap().alts, "éěë");
    }

    #[test]
    fn key_at_hits_and_snaps_to_nearest() {
        let l = letters(true);
        let q = l.key_for_code('q' as i32).unwrap().clone();
        assert_eq!(l.key_at(q.x + q.w / 2.0, q.y + q.h / 2.0).unwrap().code, 'q' as i32);
        // levy okraj druheho radku pismen patri klavese "a" (rozsireny hitbox)
        let a = l.key_for_code('a' as i32).unwrap().clone();
        assert_eq!(l.key_at(1.0, a.y + 5.0).unwrap().code, 'a' as i32);
        // bod mimo klavesnici -> nejblizsi klavesa
        assert!(l.key_at(-50.0, -50.0).is_some());
    }

    #[test]
    fn czech_diacritics_on_long_press() {
        for (c, alt) in [('a', 'á'), ('c', 'č'), ('d', 'ď'), ('e', 'ě'), ('n', 'ň'), ('r', 'ř'), ('s', 'š'), ('t', 'ť'), ('u', 'ů'), ('y', 'ý'), ('z', 'ž')] {
            assert!(letter_alts(c).contains(alt), "{c} -> {alt}");
        }
    }

    #[test]
    fn symbols_layout_has_digits_and_back_switch() {
        let l = Layout::build(1440.0, 1000.0, true, MODE_SYMBOLS, true, "x");
        assert!(l.key_for_code('7' as i32).is_some());
        assert!(l.key_for_code(CODE_LETTERS).is_some());
        assert!(l.key_for_code(CODE_SYMBOLS2).is_some());
        assert!(l.geom().present.iter().all(|&p| !p));
    }

    #[test]
    fn bottom_row_has_macro_key_instead_of_globe() {
        let l = letters(true);
        assert!(l.key_for_code(CODE_MACRO).is_some());
        assert!(l.key_for_code(CODE_GLOBE).is_none());
        assert_eq!(l.key_for_code(CODE_MACRO).unwrap().kind, KIND_MACRO);
    }

    fn bottom_layout(mode: u8, e: KeyPlace, m: KeyPlace) -> Layout {
        Layout::build_with(1440.0, 1000.0, true, mode, true, "x", BottomKeys { emoji: e, macros: m, ..BottomKeys::default() })
    }

    fn bottom_keys_of(l: &Layout) -> Vec<&Key> {
        let y = l.keys.iter().map(|k| k.y).fold(0.0f32, f32::max);
        l.keys.iter().filter(|k| k.y == y).collect()
    }

    #[test]
    fn bottom_keys_fill_full_width_in_every_combination() {
        use KeyPlace::*;
        let places = [Left, Right, Hidden];
        for mode in [MODE_LETTERS, MODE_SYMBOLS, MODE_SYMBOLS2] {
            for e in places {
                for m in places {
                    let l = bottom_layout(mode, e, m);
                    let row = bottom_keys_of(&l);
                    let sum: f32 = row.iter().map(|k| k.vw).sum();
                    assert!((sum - 1440.0).abs() < 0.5, "{mode} {e:?} {m:?}: {sum}");
                    assert!(row[0].vx.abs() < 0.5);
                    let space = row.iter().find(|k| k.code == CODE_SPACE).unwrap();
                    let hidden = [e, m].iter().filter(|p| **p == Hidden).count() as f32;
                    assert!((space.vw - (3.2 + hidden) * 144.0).abs() < 0.5);
                    assert_eq!(row.iter().any(|k| k.code == CODE_EMOJI), e != Hidden);
                    assert_eq!(row.iter().any(|k| k.code == CODE_MACRO), m != Hidden);
                }
            }
        }
    }

    #[test]
    fn bottom_keys_order() {
        use KeyPlace::*;
        let order = |e, m| -> Vec<i32> { bottom_keys_of(&bottom_layout(MODE_LETTERS, e, m)).iter().map(|k| k.code).collect() };
        let (c, d) = (',' as i32, '.' as i32);
        assert_eq!(order(Left, Left), vec![CODE_SYMBOLS, CODE_EMOJI, c, CODE_MACRO, CODE_SPACE, d, CODE_ENTER]);
        assert_eq!(order(Right, Left), vec![CODE_SYMBOLS, c, CODE_MACRO, CODE_SPACE, CODE_EMOJI, d, CODE_ENTER]);
        assert_eq!(order(Right, Right), vec![CODE_SYMBOLS, c, CODE_SPACE, CODE_EMOJI, CODE_MACRO, d, CODE_ENTER]);
        assert_eq!(order(Hidden, Right), vec![CODE_SYMBOLS, c, CODE_SPACE, CODE_MACRO, d, CODE_ENTER]);
        assert_eq!(order(Hidden, Hidden), vec![CODE_SYMBOLS, c, CODE_SPACE, d, CODE_ENTER]);
    }

    fn map_of(pairs: &[(&str, &str)]) -> BTreeMap<String, String> {
        pairs.iter().map(|(a, b)| (a.to_string(), b.to_string())).collect()
    }

    #[test]
    fn key_map_swaps_letters_and_geometry() {
        let before = letters(true).geom();
        let mut l = letters(true);
        l.apply_key_map(&map_of(&[("q", "w"), ("w", "q")]));
        let k = l.keys.iter().find(|k| k.src == 'q' as i32).unwrap();
        assert_eq!((k.code, k.label.as_str()), ('w' as i32, "w"));
        let g = l.geom();
        let (qi, wi) = (('q' as u8 - b'a') as usize, ('w' as u8 - b'a') as usize);
        assert_eq!(g.centers[wi], before.centers[qi]);
        assert_eq!(g.centers[qi], before.centers[wi]);
        assert!(g.present.iter().all(|&p| p));
    }

    #[test]
    fn key_map_to_non_letter_removes_it_from_geom() {
        let mut l = Layout::build(1440.0, 1000.0, true, MODE_LETTERS, false, "x");
        l.apply_key_map(&map_of(&[("q", "!")]));
        let g = l.geom();
        assert!(!g.present[('q' as u8 - b'a') as usize]);
        let k = l.keys.iter().find(|k| k.src == 'q' as i32).unwrap();
        assert_eq!((k.code, k.alts.as_str(), k.src), ('!' as i32, "1", 'q' as i32)); // jen cislice
    }

    #[test]
    fn key_map_adjusts_alts() {
        let mut l = Layout::build(1440.0, 1000.0, true, MODE_LETTERS, false, "x");
        l.apply_key_map(&map_of(&[("e", "a"), (",", ";"), ("r", "5")]));
        // pismeno -> pismeno: cislice zustane, diakritika podle noveho pismene
        assert_eq!(l.keys.iter().find(|k| k.src == 'e' as i32).unwrap().alts, "3áä");
        // znak -> znak: puvodni alts se nemeni
        let comma = l.keys.iter().find(|k| k.src == ',' as i32).unwrap();
        assert_eq!((comma.code, comma.alts.as_str()), (';' as i32, "'\"-_/@"));
        // pismeno -> ne-pismeno: jen cislice (r je v horni rade s cislici 4)
        assert_eq!(l.keys.iter().find(|k| k.src == 'r' as i32).unwrap().alts, "4");
        // nemapovane klavesy zustavaji
        let w = l.keys.iter().find(|k| k.src == 'w' as i32).unwrap();
        assert_eq!((w.code, w.alts.as_str()), ('w' as i32, "2"));
    }

    #[test]
    fn key_alts_override_and_clear() {
        let mut l = Layout::build(1440.0, 1000.0, true, MODE_LETTERS, false, "x");
        l.apply_key_alts(&map_of(&[("a", "xy"), ("e", ""), ("⏎", "z")]));
        let alts = |l: &Layout, c: char| l.keys.iter().find(|k| k.src == c as i32).unwrap().alts.clone();
        assert_eq!(alts(&l, 'a'), "xy");
        assert_eq!(alts(&l, 'e'), "");
        assert_eq!(alts(&l, 'o'), "9óöô"); // nedotcene klavesy zustavaji
        assert!(l.keys.iter().filter(|k| k.kind != KIND_CHAR).all(|k| k.alts.is_empty()));
    }

    #[test]
    fn key_alts_work_with_key_map() {
        let mut l = Layout::build(1440.0, 1000.0, true, MODE_LETTERS, false, "x");
        l.apply_key_map(&map_of(&[("e", "a")]));
        l.apply_key_alts(&map_of(&[("e", "q")]));
        let k = l.keys.iter().find(|k| k.src == 'e' as i32).unwrap();
        assert_eq!((k.code, k.alts.as_str()), ('a' as i32, "q"));
    }

    #[test]
    fn key_map_ignores_special_keys() {
        let mut l = letters(true);
        l.apply_key_map(&map_of(&[("⏎", "x"), (" ", "x")]));
        assert!(l.key_for_code(CODE_SPACE).is_some() && l.key_for_code(CODE_ENTER).is_some());
        assert!(l.keys.iter().filter(|k| k.kind != KIND_CHAR).all(|k| k.src == k.code));
    }

    #[test]
    fn json_contains_src() {
        let mut l = letters(true);
        l.apply_key_map(&map_of(&[("q", "w")]));
        let v: serde_json::Value = serde_json::from_str(&l.to_json()).unwrap();
        let keys = v["keys"].as_array().unwrap();
        assert!(keys.iter().all(|k| k["src"].is_i64()));
        assert!(keys.iter().any(|k| k["src"] == 'q' as i32 && k["code"] == 'w' as i32));
    }

    #[test]
    fn json_contains_all_keys() {
        let l = letters(true);
        let v: serde_json::Value = serde_json::from_str(&l.to_json()).unwrap();
        assert_eq!(v["keys"].as_array().unwrap().len(), l.keys.len());
        assert_eq!(v["rows"], 5);
    }

    #[test]
    fn json_marks_number_row() {
        let flag = |mode: u8, number_row: bool| {
            let l = Layout::build(1440.0, 1000.0, true, mode, number_row, "x");
            let v: serde_json::Value = serde_json::from_str(&l.to_json()).unwrap();
            v["number_row"].as_bool().unwrap()
        };
        assert!(flag(MODE_LETTERS, true));
        assert!(!flag(MODE_LETTERS, false));
        assert!(!flag(MODE_SYMBOLS, true));
        assert!(!flag(MODE_SYMBOLS2, true));
    }

    fn swapped(mode: u8, me: bool, sd: bool) -> Layout {
        Layout::build_with(
            1440.0,
            1000.0,
            true,
            mode,
            true,
            "x",
            BottomKeys { swap_mode_enter: me, swap_shift_delete: sd, ..BottomKeys::default() },
        )
    }

    fn key_by_kind(l: &Layout, kind: u8, bottom: bool) -> Key {
        let by = l.keys.iter().map(|k| k.y).fold(0.0f32, f32::max);
        l.keys.iter().find(|k| k.kind == kind && ((k.y == by) == bottom)).unwrap().clone()
    }

    fn sorted_codes(l: &Layout) -> Vec<i32> {
        let mut v: Vec<i32> = l.keys.iter().map(|k| k.code).collect();
        v.sort();
        v
    }

    fn assert_no_overlap(l: &Layout) {
        for (i, a) in l.keys.iter().enumerate() {
            for b in l.keys.iter().skip(i + 1) {
                let ox = a.x < b.x + b.w - 0.01 && b.x < a.x + a.w - 0.01;
                let oy = a.y < b.y + b.h - 0.01 && b.y < a.y + a.h - 0.01;
                assert!(!(ox && oy), "{} overlaps {}", a.label, b.label);
            }
        }
    }

    #[test]
    fn swap_mode_and_enter_in_every_mode() {
        for mode in [MODE_LETTERS, MODE_SYMBOLS, MODE_SYMBOLS2] {
            let base = swapped(mode, false, false);
            let l = swapped(mode, true, false);
            let (bm, be) = (key_by_kind(&base, KIND_MODE, true), key_by_kind(&base, KIND_ENTER, true));
            let (m, e) = (key_by_kind(&l, KIND_MODE, true), key_by_kind(&l, KIND_ENTER, true));
            // Enter je ve slotu puvodniho ?123 / ABC a naopak
            assert_eq!((e.x, e.vx, e.vw, e.y), (bm.x, bm.vx, bm.vw, bm.y), "mode {mode}");
            assert_eq!((m.x, m.vx, m.vw, m.y), (be.x, be.vx, be.vw, be.y), "mode {mode}");
            assert_eq!(e.code, CODE_ENTER);
            assert_eq!(m.code, bm.code);
            assert_eq!(sorted_codes(&base), sorted_codes(&l));
            assert_eq!(base.keys.len(), l.keys.len());
            assert_no_overlap(&l);
            // Shift/Backspace zustavaji
            assert_eq!(key_by_kind(&base, KIND_BACKSPACE, false).x, key_by_kind(&l, KIND_BACKSPACE, false).x);
        }
    }

    #[test]
    fn swap_shift_and_backspace_in_every_mode() {
        for mode in [MODE_LETTERS, MODE_SYMBOLS, MODE_SYMBOLS2] {
            let base = swapped(mode, false, false);
            let l = swapped(mode, false, true);
            let bd = key_by_kind(&base, KIND_BACKSPACE, false);
            let slot = base.keys.iter().find(|k| k.y == bd.y && k.x == 0.0).unwrap().clone();
            let d = key_by_kind(&l, KIND_BACKSPACE, false);
            let moved = l.keys.iter().find(|k| k.code == slot.code).unwrap();
            assert_eq!((d.x, d.vx, d.vw), (slot.x, slot.vx, slot.vw), "mode {mode}");
            assert_eq!((moved.x, moved.vx, moved.vw, moved.y), (bd.x, bd.vx, bd.vw, bd.y), "mode {mode}");
            assert_eq!(sorted_codes(&base), sorted_codes(&l));
            assert_no_overlap(&l);
            // hitboxy stale pokryvaji cely radek
            let sum: f32 = l.keys.iter().filter(|k| k.y == bd.y).map(|k| k.w).sum();
            assert!((sum - 1440.0).abs() < 0.5);
        }
    }

    #[test]
    fn swap_both_and_swap_off_is_identity() {
        let base = swapped(MODE_LETTERS, false, false);
        assert_eq!(base.keys, Layout::build(1440.0, 1000.0, true, MODE_LETTERS, true, "x").keys);
        let l = swapped(MODE_LETTERS, true, true);
        assert_eq!(key_by_kind(&l, KIND_SHIFT, false).x, key_by_kind(&base, KIND_BACKSPACE, false).x);
        assert_eq!(key_by_kind(&l, KIND_ENTER, true).x, key_by_kind(&base, KIND_MODE, true).x);
        assert_eq!(sorted_codes(&base), sorted_codes(&l));
        assert_no_overlap(&l);
        // pismena (swipe geometrie) se nemeni
        assert_eq!(base.geom(), l.geom());
        // swap funguje i bez rady cislic a se skrytymi tlacitky
        let l2 = Layout::build_with(
            1440.0,
            1000.0,
            true,
            MODE_LETTERS,
            false,
            "x",
            BottomKeys { emoji: KeyPlace::Hidden, macros: KeyPlace::Right, swap_mode_enter: true, swap_shift_delete: true },
        );
        assert_eq!(l2.keys.len(), Layout::build(1440.0, 1000.0, true, MODE_LETTERS, false, "x").keys.len() - 1);
        assert_no_overlap(&l2);
    }
}
