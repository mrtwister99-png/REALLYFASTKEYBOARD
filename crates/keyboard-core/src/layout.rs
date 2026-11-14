//! Rozlozeni klaves: CZ QWERTZ, EN QWERTY, cisla a symboly.
//! Rust pocita geometrii (v pixelech), Kotlin ji jen vykresli. Stejnou geometrii
//! pouziva swipe decoder, takze se vykresleni a dekodovani nemohou rozejit.

use serde::Serialize;

pub const KIND_CHAR: u8 = 0;
pub const KIND_SHIFT: u8 = 1;
pub const KIND_BACKSPACE: u8 = 2;
pub const KIND_MODE: u8 = 3;
pub const KIND_ENTER: u8 = 4;
pub const KIND_SPACE: u8 = 5;
pub const KIND_GLOBE: u8 = 6;
pub const KIND_EMOJI: u8 = 7;

pub const CODE_SHIFT: i32 = -1;
pub const CODE_BACKSPACE: i32 = -2;
pub const CODE_SYMBOLS: i32 = -3;
pub const CODE_LETTERS: i32 = -4;
pub const CODE_SYMBOLS2: i32 = -5;
pub const CODE_ENTER: i32 = -6;
pub const CODE_GLOBE: i32 = -7;
pub const CODE_EMOJI: i32 = -8;
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
}

/// Stredy pismen a-z v normalizovanych jednotkach (1 = sirka klavesy / vyska radku).
#[derive(Debug, Clone, Copy)]
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

fn bottom_row(mode: u8, space_label: &str) -> Vec<Def> {
    let switch = if mode == MODE_LETTERS {
        special(1.4, CODE_SYMBOLS, KIND_MODE, "?123", "Symboly a čísla")
    } else {
        special(1.4, CODE_LETTERS, KIND_MODE, "ABC", "Písmena")
    };
    vec![
        switch,
        special(1.0, CODE_EMOJI, KIND_EMOJI, "☺", "Emoji"),
        ch(',', "'\"-_/@"),
        special(1.0, CODE_GLOBE, KIND_GLOBE, "🌐", "Přepnout jazyk"),
        special(3.2, CODE_SPACE, KIND_SPACE, space_label, "Mezerník"),
        ch('.', "?!:;…"),
        special(1.4, CODE_ENTER, KIND_ENTER, "⏎", "Enter"),
    ]
}

fn wrap_specials(left: Def, mut middle: Vec<Def>, right: Def) -> Vec<Def> {
    let mut row = vec![left];
    row.append(&mut middle);
    row.push(right);
    row
}

fn rows_for(qwertz: bool, mode: u8, number_row: bool, space_label: &str) -> Vec<Vec<Def>> {
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
    rows.push(bottom_row(mode, space_label));
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
        let width = if width.is_finite() && width > 1.0 { width } else { 1080.0 };
        let height = if height.is_finite() && height > 1.0 { height } else { 800.0 };
        let rows = rows_for(qwertz, mode, number_row, space_label);
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
                    kind: d.kind,
                    label: d.label,
                    alts: d.alts,
                    desc: d.desc,
                });
                x += vw;
            }
        }
        Layout { keys, width, height, rows: n_rows, unit_w, row_h, mode }
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
            keys: &'a [Key],
        }
        serde_json::to_string(&Out { width: self.width, height: self.height, rows: self.rows, mode: self.mode, keys: &self.keys })
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
    fn json_contains_all_keys() {
        let l = letters(true);
        let v: serde_json::Value = serde_json::from_str(&l.to_json()).unwrap();
        assert_eq!(v["keys"].as_array().unwrap().len(), l.keys.len());
        assert_eq!(v["rows"], 5);
    }
}
