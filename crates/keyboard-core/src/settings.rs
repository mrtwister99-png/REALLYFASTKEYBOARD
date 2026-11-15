//! Nastaveni klavesnice. Jediny zdroj pravdy: `settings.json` v app files dir.
//! Kotlin (SettingsActivity) i Rust ctou a zapisuji stejny soubor pres tento modul.

use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

pub const LANGUAGES: [&str; 3] = ["cs", "en", "cs+en"];
pub const LAYOUTS: [&str; 2] = ["qwertz", "qwerty"];
pub const KEY_PLACES: [&str; 3] = ["left", "right", "hidden"];
pub const THEMES: [&str; 4] = ["dark", "light", "oled_black", "custom"];
pub const GLOW_STYLES: [&str; 3] = ["blink", "fill", "fly"];
pub const TAP_EFFECTS: [&str; 3] = ["neon", "glitch", "paint"];

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct Settings {
    /// Verze schematu nastaveni (pro migrace vychozich hodnot). Chybejici pole = 0.
    #[serde(default)]
    pub settings_version: u32,
    pub language: String,
    pub layout: String,
    pub swipe_enabled: bool,
    pub swipe_sensitivity: f32,
    pub autocorrect: bool,
    pub predictions: bool,
    pub learn_from_typing: bool,
    pub vibration_ms: u32,
    pub sound_enabled: bool,
    pub key_height_dp: u32,
    pub keyboard_height_percent: u32,
    pub theme: String,
    pub accent_color: String,
    pub show_number_row: bool,
    pub show_suggestions: bool,
    pub auto_capitalize: bool,
    pub double_space_to_period: bool,
    pub long_press_delay_ms: u32,
    // --- vlastni tema ---
    pub custom_background: String,
    pub custom_key: String,
    pub custom_text: String,
    /// Diktovani: podrzeni Enteru prepise rec na text (systemove rozpoznavani reci).
    pub voice_input: bool,
    // --- vzhled (Faze 2) ---
    /// Bublina s pismenem nad stisknutou klavesou.
    pub key_popup: bool,
    /// Male napovedy znaku pro dlouhy stisk v rohu klaves.
    pub key_hints: bool,
    /// Jemny stin pod klavesami.
    pub key_shadow: bool,
    /// Zaobleni klaves v dp.
    pub key_radius_dp: u32,
    /// Animace (stisk, prechody panelu, navrhy).
    pub animations: bool,
    /// Stopa za prstem pri swipe.
    pub swipe_trail: bool,
    /// Rozsviceni klaves: projete klavesy behem tahu + pismena vysledneho slova.
    pub swipe_glow: bool,
    /// Prizpusobeni swipe stylu psani (posun stredu klaves podle potvrzenych tahu).
    pub swipe_adapt: bool,
    /// Zaznam tahu pro ladeni: ulozi drahy tahu spolu s potvrzenymi slovy (jen v zarizeni).
    pub swipe_log: bool,
    /// Neonove rozsviceni klavesy pri klepnuti.
    pub key_glow: bool,
    /// Premapovani klaves: puvodni znak pozice -> znak, ktery tam je.
    pub key_map: BTreeMap<String, String>,
    /// Znaky pro dlouhy stisk: puvodni znak pozice -> retezec variant (prazdny = zadne).
    pub key_alts: BTreeMap<String, String>,
    /// Poloha male napovedy na klavese (viz HINT_POSITIONS).
    pub key_hint_pos: String,
    /// Volna poloha male napovedy: x (0 = vlevo, 1 = vpravo), y (0 = nahore, 1 = dole);
    /// mimo 0..1 (az -1..2) lezi napoveda za okrajem klavesy. Chybejici hodnota (NaN) se odvodi
    /// ze stareho `key_hint_pos`.
    #[serde(default = "hint_unset")]
    pub key_hint_x: f32,
    #[serde(default = "hint_unset")]
    pub key_hint_y: f32,
    /// Barva znaku napovedy ("" = automaticka podle motivu).
    pub hint_color: String,
    /// Velikost znaku napovedy v procentech (100 = vychozi).
    pub hint_percent: u32,
    /// Efekt po klepnuti na klavesu: "neon", "glitch" nebo "paint". Zapina ho `key_glow`.
    pub tap_effect: String,
    /// Barva neonu po klepnuti.
    pub neon_color: String,
    /// Barva pismen po uspesnem tahu.
    pub glow_color: String,
    /// Animace pismen po uspesnem tahu: "blink" (postupne zablikani) nebo "fill" (vybarveni klaves).
    pub glow_style: String,
    /// Vodorovna mezera mezi klavesami v dp.
    pub key_gap_dp: u32,
    /// Svisla mezera mezi radky v dp. Chybejici hodnota (-1) se odvodi z `key_gap_dp` (1,6x).
    #[serde(default = "row_gap_unset")]
    pub row_gap_dp: i32,
    /// Barva bubliny nad stisknutou klavesou ("" = automaticka podle motivu).
    pub popup_color: String,
    /// Barva listy znaku pri podrzeni klavesy ("" = automaticka podle motivu).
    pub alts_color: String,
    /// Cara mezi radou cislic a pismeny.
    pub number_divider: bool,
    /// Prohozene pozice ?123/ABC a Enter.
    pub swap_mode_enter: bool,
    /// Prohozene pozice Shift (nebo prepinace symbolu) a Backspace.
    pub swap_shift_delete: bool,
    /// Velikost znaku na klavesach v procentech (100 = vychozi).
    pub key_text_percent: u32,
    /// Tlacitko emoji ve spodni rade: "left" (vlevo od mezerniku), "right" (vpravo), "hidden".
    pub emoji_key: String,
    /// Tlacitko maker ve spodni rade: stejne hodnoty jako `emoji_key`.
    pub macro_key: String,
    // --- gesta (kazde lze vypnout) ---
    pub gesture_delete_word: bool,
    pub gesture_space_cursor: bool,
    pub gesture_swipe_up_caps: bool,
    pub gesture_swipe_down_hide: bool,
    pub gesture_pinch_resize: bool,
    // --- schranka ---
    pub clipboard_enabled: bool,
    pub clipboard_ttl_minutes: u32,
    // --- barvy klaves a pozadi navic ---
    /// Barva znaku cislicovych klaves ("" = automaticka).
    pub digit_text_color: String,
    /// Barva cislicovych klaves ("" = automaticka).
    pub digit_key_color: String,
    /// Druha barva klaves: prechod shora (barva klavesy) dolu (tato barva); "" = vypnuto.
    pub key_color2: String,
    /// Druha barva pozadi: prechod shora (pozadi) dolu (tato barva); "" = vypnuto.
    pub background_color2: String,
    /// Tri barvy efektu "paint" (klepnuti obarvi klavesu dalsi barvou v poradi).
    pub paint_color1: String,
    pub paint_color2: String,
    pub paint_color3: String,
    /// Poloha hlavniho znaku na klavese: x, y v 0..1 (0,5 = uprostred).
    pub key_label_x: f32,
    pub key_label_y: f32,
    /// Barva znaku klavesy, kterou prochazi prst pri tahu ("" = automaticka).
    pub passed_text_color: String,
    /// Barva znaku po klepnuti ("" = vypnuto).
    pub tap_text_color: String,
    /// Barva znaku klavesy primo pod prstem pri tahu ("" = automaticka).
    pub hover_text_color: String,
    /// Tloustka pisma znaku (pismena) a cislic: 100..900 po 100 (400 = normalni).
    pub label_weight: u32,
    pub digit_weight: u32,
    /// Makro prirazene primo klavese: puvodni znak pozice -> id makra.
    pub key_macros: BTreeMap<String, u32>,
}

impl Default for Settings {
    fn default() -> Self {
        Settings {
            settings_version: SETTINGS_VERSION,
            language: "cs+en".into(),
            layout: "qwertz".into(),
            swipe_enabled: true,
            swipe_sensitivity: 1.0,
            autocorrect: true,
            predictions: true,
            learn_from_typing: true,
            vibration_ms: 10,
            sound_enabled: false,
            key_height_dp: 56,
            keyboard_height_percent: 38,
            theme: "dark".into(),
            accent_color: DEFAULT_ACCENT.into(),
            show_number_row: true,
            show_suggestions: true,
            auto_capitalize: true,
            double_space_to_period: true,
            long_press_delay_ms: 250,
            custom_background: "#111113".into(),
            custom_key: "#26262B".into(),
            custom_text: "#F4F4F5".into(),
            voice_input: true,
            key_popup: true,
            key_hints: true,
            key_shadow: true,
            key_radius_dp: 10,
            animations: true,
            swipe_trail: true,
            swipe_glow: true,
            swipe_adapt: true,
            swipe_log: false,
            key_glow: true,
            key_map: BTreeMap::new(),
            key_alts: BTreeMap::new(),
            key_hint_pos: "top_right".into(),
            key_hint_x: 1.0,
            key_hint_y: 0.0,
            hint_color: String::new(),
            hint_percent: 100,
            tap_effect: "neon".into(),
            neon_color: DEFAULT_ACCENT.into(),
            glow_color: DEFAULT_ACCENT.into(),
            glow_style: "blink".into(),
            key_gap_dp: 5,
            row_gap_dp: 8,
            popup_color: String::new(),
            alts_color: String::new(),
            number_divider: true,
            swap_mode_enter: false,
            swap_shift_delete: false,
            key_text_percent: 100,
            emoji_key: "left".into(),
            macro_key: "left".into(),
            gesture_delete_word: true,
            gesture_space_cursor: true,
            gesture_swipe_up_caps: true,
            gesture_swipe_down_hide: true,
            gesture_pinch_resize: true,
            clipboard_enabled: true,
            clipboard_ttl_minutes: 60,
            digit_text_color: String::new(),
            digit_key_color: String::new(),
            key_color2: String::new(),
            background_color2: String::new(),
            paint_color1: "#0080FF".into(),
            paint_color2: "#FF5100".into(),
            paint_color3: "#6A02E8".into(),
            key_label_x: 0.5,
            key_label_y: 0.5,
            passed_text_color: String::new(),
            tap_text_color: String::new(),
            hover_text_color: String::new(),
            label_weight: 400,
            digit_weight: 400,
            key_macros: BTreeMap::new(),
        }
    }
}

/// Zaokrouhli tloustku pisma na nejblizsi stovku a omezi na 100..900.
fn round_weight(w: u32) -> u32 {
    (w.saturating_add(50) / 100 * 100).clamp(100, 900)
}

/// Nejvyse tolik premapovanych klaves se drzi v nastaveni.
pub const MAX_KEY_MAP: usize = 64;
/// Nejvyse tolik klaves s vlastnimi znaky pro dlouhy stisk.
pub const MAX_KEY_ALTS: usize = 64;
/// Nejvyse tolik variant na klavesu.
pub const MAX_ALTS_LEN: usize = 12;
/// Nejvyse tolik klaves s prirazenym makrem.
pub const MAX_KEY_MACROS: usize = 16;
pub const HINT_POSITIONS: [&str; 6] =
    ["top_right", "top_left", "top_center", "bottom_right", "bottom_left", "bottom_center"];

/// Hodnota "poloha napovedy neni zadana" (pro migraci ze `key_hint_pos`).
fn hint_unset() -> f32 {
    f32::NAN
}

/// Hodnota "mezera mezi radky neni zadana" (odvodi se z `key_gap_dp`).
fn row_gap_unset() -> i32 {
    -1
}

/// Jediny znak, ktery neni mezera ani ridici.
fn single_char(s: &str) -> Option<char> {
    let mut it = s.chars();
    let c = it.next()?;
    if it.next().is_some() || c.is_whitespace() || c.is_control() {
        return None;
    }
    Some(c)
}

pub const SETTINGS_VERSION: u32 = 2;
pub const DEFAULT_ACCENT: &str = "#0080FF";
/// Vychozi accent barva do verze 1 (oranzova).
const LEGACY_ACCENT: &str = "#F59E0B";

/// `#RRGGBB` nebo `#AARRGGBB`.
pub fn is_hex_color(s: &str) -> bool {
    let b = s.as_bytes();
    (b.len() == 7 || b.len() == 9) && b[0] == b'#' && b[1..].iter().all(|c| c.is_ascii_hexdigit())
}

fn fix_color(value: &mut String, default: &str) {
    if is_hex_color(value) {
        *value = value.to_ascii_uppercase();
    } else {
        *value = default.to_string();
    }
}

/// Prazdna hodnota = automaticka barva; jinak platna barva, nebo prazdno.
fn fix_optional_color(value: &mut String) {
    if is_hex_color(value) {
        *value = value.to_ascii_uppercase();
    } else {
        value.clear();
    }
}

/// Stara pojmenovana poloha napovedy -> (x, y).
fn legacy_hint_xy(pos: &str) -> (f32, f32) {
    let x = match pos {
        "top_left" | "bottom_left" => 0.0,
        "top_center" | "bottom_center" => 0.5,
        _ => 1.0,
    };
    let y = if pos.starts_with("bottom") { 1.0 } else { 0.0 };
    (x, y)
}

impl Settings {
    /// Orizne hodnoty do povolenych rozsahu a nahradi neplatne volby vychozimi.
    pub fn validate(&mut self) {
        let d = Settings::default();
        if !LANGUAGES.contains(&self.language.as_str()) {
            self.language = d.language.clone();
        }
        if !LAYOUTS.contains(&self.layout.as_str()) {
            self.layout = d.layout.clone();
        }
        if !THEMES.contains(&self.theme.as_str()) {
            self.theme = d.theme.clone();
        }
        if !self.swipe_sensitivity.is_finite() {
            self.swipe_sensitivity = 1.0;
        }
        self.swipe_sensitivity = self.swipe_sensitivity.clamp(0.5, 1.5);
        self.vibration_ms = self.vibration_ms.min(50);
        self.key_height_dp = self.key_height_dp.clamp(48, 72);
        self.keyboard_height_percent = self.keyboard_height_percent.clamp(30, 50);
        self.long_press_delay_ms = self.long_press_delay_ms.clamp(150, 500);
        self.clipboard_ttl_minutes = self.clipboard_ttl_minutes.clamp(1, 1440);
        self.key_radius_dp = self.key_radius_dp.clamp(2, 22);
        self.key_gap_dp = self.key_gap_dp.clamp(1, 10);
        if self.row_gap_dp < 0 {
            self.row_gap_dp = (self.key_gap_dp as f32 * 1.6).round() as i32;
        }
        self.row_gap_dp = self.row_gap_dp.clamp(2, 16);
        self.key_text_percent = self.key_text_percent.clamp(70, 150);
        if !GLOW_STYLES.contains(&self.glow_style.as_str()) {
            self.glow_style = d.glow_style.clone();
        }
        if self.tap_effect == "vanish" {
            self.tap_effect = "glitch".into();
        }
        if !TAP_EFFECTS.contains(&self.tap_effect.as_str()) {
            self.tap_effect = d.tap_effect.clone();
        }
        for v in [&mut self.key_label_x, &mut self.key_label_y] {
            *v = if v.is_finite() { v.clamp(0.0, 1.0) } else { 0.5 };
        }
        self.key_macros = std::mem::take(&mut self.key_macros)
            .into_iter()
            .filter(|(k, v)| single_char(k).is_some() && *v > 0)
            .take(MAX_KEY_MACROS)
            .collect();
        self.hint_percent = self.hint_percent.clamp(60, 180);
        self.key_map = std::mem::take(&mut self.key_map)
            .into_iter()
            .filter(|(k, v)| matches!((single_char(k), single_char(v)), (Some(a), Some(b)) if a != b))
            .take(MAX_KEY_MAP)
            .collect();
        self.key_alts = std::mem::take(&mut self.key_alts)
            .into_iter()
            .filter(|(k, _)| single_char(k).is_some())
            .map(|(k, v)| {
                let v: String = v.chars().filter(|c| !c.is_whitespace() && !c.is_control()).take(MAX_ALTS_LEN).collect();
                (k, v)
            })
            .take(MAX_KEY_ALTS)
            .collect();
        if !HINT_POSITIONS.contains(&self.key_hint_pos.as_str()) {
            self.key_hint_pos = d.key_hint_pos.clone();
        }
        let ok = |v: f32| v.is_finite() && (-1.0..=2.0).contains(&v);
        if !ok(self.key_hint_x) || !ok(self.key_hint_y) {
            let (x, y) = legacy_hint_xy(&self.key_hint_pos);
            self.key_hint_x = x;
            self.key_hint_y = y;
        }
        if !KEY_PLACES.contains(&self.emoji_key.as_str()) {
            self.emoji_key = d.emoji_key.clone();
        }
        if !KEY_PLACES.contains(&self.macro_key.as_str()) {
            self.macro_key = d.macro_key.clone();
        }
        fix_optional_color(&mut self.popup_color);
        fix_optional_color(&mut self.alts_color);
        fix_optional_color(&mut self.hint_color);
        fix_optional_color(&mut self.passed_text_color);
        fix_optional_color(&mut self.tap_text_color);
        fix_optional_color(&mut self.hover_text_color);
        self.label_weight = round_weight(self.label_weight);
        self.digit_weight = round_weight(self.digit_weight);
        fix_optional_color(&mut self.digit_text_color);
        fix_optional_color(&mut self.digit_key_color);
        fix_optional_color(&mut self.key_color2);
        fix_optional_color(&mut self.background_color2);
        fix_color(&mut self.paint_color1, &d.paint_color1);
        fix_color(&mut self.paint_color2, &d.paint_color2);
        fix_color(&mut self.paint_color3, &d.paint_color3);
        fix_color(&mut self.neon_color, &d.neon_color);
        fix_color(&mut self.glow_color, &d.glow_color);
        fix_color(&mut self.accent_color, &d.accent_color);
        fix_color(&mut self.custom_background, &d.custom_background);
        fix_color(&mut self.custom_key, &d.custom_key);
        fix_color(&mut self.custom_text, &d.custom_text);
    }

    /// Nacte nastaveni z JSON. Chybejici pole dostanou vychozi hodnoty,
    /// neplatny JSON vraci `None`.
    pub fn from_json(json: &str) -> Option<Settings> {
        let mut s: Settings = serde_json::from_str(json).ok()?;
        if s.settings_version < SETTINGS_VERSION {
            // Migrace: kdo mel puvodni vychozi oranzovou, dostane novou vychozi modrou.
            if s.accent_color.eq_ignore_ascii_case(LEGACY_ACCENT) {
                s.accent_color = DEFAULT_ACCENT.into();
            }
            s.settings_version = SETTINGS_VERSION;
        }
        s.validate();
        Some(s)
    }

    pub fn to_json(&self) -> String {
        serde_json::to_string_pretty(self).unwrap_or_else(|_| "{}".to_string())
    }

    pub fn uses_lang(&self, lang: &str) -> bool {
        self.language.split('+').any(|l| l == lang)
    }

    pub fn is_qwertz(&self) -> bool {
        self.layout == "qwertz"
    }

    /// Dalsi jazyk v poradi cs -> en -> cs+en (globus / dlouhy stisk mezerniku).
    /// Rozlozeni se prepne spolu s jazykem.
    pub fn cycle_language(&mut self) {
        let (lang, layout) = match self.language.as_str() {
            "cs" => ("en", "qwerty"),
            "en" => ("cs+en", "qwertz"),
            _ => ("cs", "qwertz"),
        };
        self.language = lang.into();
        self.layout = layout.into();
    }

    pub fn language_label(&self) -> &'static str {
        match self.language.as_str() {
            "cs" => "Čeština",
            "en" => "English",
            _ => "Čeština · English",
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn defaults_match_spec() {
        let s = Settings::default();
        assert_eq!(s.language, "cs+en");
        assert_eq!(s.layout, "qwertz");
        assert!(s.swipe_enabled && s.autocorrect && s.predictions && s.learn_from_typing);
        assert_eq!(s.swipe_sensitivity, 1.0);
        assert_eq!(s.vibration_ms, 10);
        assert!(!s.sound_enabled);
        assert_eq!(s.key_height_dp, 56);
        assert_eq!(s.keyboard_height_percent, 38);
        assert_eq!(s.theme, "dark");
        assert_eq!(s.accent_color, "#0080FF");
        assert!(s.show_number_row && s.show_suggestions && s.auto_capitalize && s.double_space_to_period);
        assert_eq!(s.long_press_delay_ms, 250);
    }

    #[test]
    fn json_roundtrip() {
        let mut s = Settings::default();
        s.language = "en".into();
        s.swipe_sensitivity = 1.3;
        s.theme = "oled_black".into();
        let back = Settings::from_json(&s.to_json()).unwrap();
        assert_eq!(back, s);
    }

    #[test]
    fn missing_fields_get_defaults() {
        let s = Settings::from_json(r#"{"language":"cs","vibration_ms":0}"#).unwrap();
        assert_eq!(s.language, "cs");
        assert_eq!(s.vibration_ms, 0);
        assert_eq!(s.key_height_dp, 56);
        assert!(s.swipe_enabled);
    }

    #[test]
    fn validation_clamps_ranges() {
        let s = Settings::from_json(
            r#"{"swipe_sensitivity":9.0,"vibration_ms":500,"key_height_dp":10,
                "keyboard_height_percent":90,"long_press_delay_ms":1}"#,
        )
        .unwrap();
        assert_eq!(s.swipe_sensitivity, 1.5);
        assert_eq!(s.vibration_ms, 50);
        assert_eq!(s.key_height_dp, 48);
        assert_eq!(s.keyboard_height_percent, 50);
        assert_eq!(s.long_press_delay_ms, 150);
    }

    #[test]
    fn validation_rejects_bad_enums_and_colors() {
        let s = Settings::from_json(r##"{"language":"de","layout":"azerty","theme":"pink","accent_color":"red"}"##)
            .unwrap();
        assert_eq!(s.language, "cs+en");
        assert_eq!(s.layout, "qwertz");
        assert_eq!(s.theme, "dark");
        assert_eq!(s.accent_color, "#0080FF");
        assert!(Settings::from_json("not json").is_none());
    }

    #[test]
    fn appearance_defaults_and_limits() {
        let s = Settings::default();
        assert!(s.key_popup && s.key_hints && s.key_shadow && s.animations && s.swipe_trail);
        assert!(s.voice_input && s.swipe_glow && s.swipe_adapt);
        assert!(!s.swipe_log);
        assert_eq!(s.key_radius_dp, 10);
        let s = Settings::from_json(r#"{"key_radius_dp":99,"animations":false}"#).unwrap();
        assert_eq!(s.key_radius_dp, 22);
        assert!(!s.animations);
        // stary settings.json z Faze 1 (bez novych poli) se nacte s vychozimi hodnotami
        let old = Settings::from_json(r#"{"theme":"light"}"#).unwrap();
        assert!(old.key_popup && old.swipe_trail);
    }

    #[test]
    fn old_default_accent_migrates_to_blue() {
        // settings.json z verze 1 s puvodni vychozi oranzovou
        let s = Settings::from_json(r##"{"accent_color":"#F59E0B"}"##).unwrap();
        assert_eq!(s.accent_color, "#0080FF");
        assert_eq!(s.settings_version, SETTINGS_VERSION);
        // vlastni barva zustava
        let s = Settings::from_json(r##"{"accent_color":"#EF4444"}"##).unwrap();
        assert_eq!(s.accent_color, "#EF4444");
        // oranzova zvolena uz v nove verzi se neprepise
        let s = Settings::from_json(r##"{"settings_version":2,"accent_color":"#F59E0B"}"##).unwrap();
        assert_eq!(s.accent_color, "#F59E0B");
    }

    #[test]
    fn key_glow_and_key_map_defaults() {
        let s = Settings::default();
        assert!(s.key_glow);
        assert!(s.key_map.is_empty());
        let old = Settings::from_json(r#"{"theme":"light"}"#).unwrap();
        assert!(old.key_glow && old.key_map.is_empty());
    }

    #[test]
    fn invalid_key_map_entries_are_dropped() {
        let s = Settings::from_json(
            r#"{"key_map":{"a":"b","c":"c","d":"","e":"fg"," ":"x","f":" ","g":"\n","":"z","h":"ě"}}"#,
        )
        .unwrap();
        let keys: Vec<&str> = s.key_map.keys().map(String::as_str).collect();
        assert_eq!(keys, vec!["a", "h"]);
        assert_eq!(s.key_map["h"], "ě");
        // limit poctu
        let many: BTreeMap<String, String> =
            (0..100u32).map(|i| (char::from_u32(0x4E00 + i).unwrap().to_string(), "x".to_string())).collect();
        let mut s = Settings { key_map: many, ..Settings::default() };
        s.validate();
        assert_eq!(s.key_map.len(), MAX_KEY_MAP);
    }

    #[test]
    fn key_map_roundtrip() {
        let mut s = Settings::default();
        s.key_map.insert("q".into(), "w".into());
        s.key_map.insert("w".into(), "q".into());
        s.key_glow = false;
        let back = Settings::from_json(&s.to_json()).unwrap();
        assert_eq!(back, s);
    }

    #[test]
    fn key_alts_validation() {
        let s = Settings::from_json(
            r#"{"key_alts":{"a":" á ä\n","b":"","cc":"x"," ":"x","":"y","d":"123456789012345"}}"#,
        )
        .unwrap();
        let keys: Vec<&str> = s.key_alts.keys().map(String::as_str).collect();
        assert_eq!(keys, vec!["a", "b", "d"]);
        assert_eq!(s.key_alts["a"], "áä");
        assert_eq!(s.key_alts["b"], "");
        assert_eq!(s.key_alts["d"].chars().count(), MAX_ALTS_LEN);
        let many: BTreeMap<String, String> =
            (0..100u32).map(|i| (char::from_u32(0x4E00 + i).unwrap().to_string(), "x".to_string())).collect();
        let mut s = Settings { key_alts: many, ..Settings::default() };
        s.validate();
        assert_eq!(s.key_alts.len(), MAX_KEY_ALTS);
    }

    #[test]
    fn hint_pos_and_colors() {
        let s = Settings::default();
        assert_eq!(s.key_hint_pos, "top_right");
        assert_eq!((s.neon_color.as_str(), s.glow_color.as_str()), ("#0080FF", "#0080FF"));
        assert!(s.key_alts.is_empty());
        let s = Settings::from_json(r##"{"key_hint_pos":"bottom_left","neon_color":"#ff0000","glow_color":"x"}"##).unwrap();
        assert_eq!(s.key_hint_pos, "bottom_left");
        assert_eq!(s.neon_color, "#FF0000");
        assert_eq!(s.glow_color, "#0080FF");
        let s = Settings::from_json(r#"{"key_hint_pos":"middle"}"#).unwrap();
        assert_eq!(s.key_hint_pos, "top_right");
    }

    #[test]
    fn default_colors_are_blue_and_stored_values_stay() {
        let s = Settings::default();
        assert_eq!((s.accent_color.as_str(), s.neon_color.as_str(), s.glow_color.as_str()), ("#0080FF", "#0080FF", "#0080FF"));
        let s = Settings::from_json(r##"{"neon_color":"#59ACFF","glow_color":"#ffd60a"}"##).unwrap();
        assert_eq!((s.neon_color.as_str(), s.glow_color.as_str()), ("#59ACFF", "#FFD60A"));
        // chybejici nebo neplatna barva -> nova vychozi modra
        let s = Settings::from_json(r##"{"neon_color":"x"}"##).unwrap();
        assert_eq!((s.neon_color.as_str(), s.glow_color.as_str()), ("#0080FF", "#0080FF"));
    }

    #[test]
    fn glow_style_gap_and_text_size() {
        let s = Settings::default();
        assert_eq!((s.glow_style.as_str(), s.key_gap_dp, s.key_text_percent), ("blink", 5, 100));
        let s = Settings::from_json(r#"{"glow_style":"fill","key_gap_dp":7,"key_text_percent":120}"#).unwrap();
        assert_eq!((s.glow_style.as_str(), s.key_gap_dp, s.key_text_percent), ("fill", 7, 120));
        let s = Settings::from_json(r#"{"glow_style":"spin","key_gap_dp":0,"key_text_percent":10}"#).unwrap();
        assert_eq!((s.glow_style.as_str(), s.key_gap_dp, s.key_text_percent), ("blink", 1, 70));
        let s = Settings::from_json(r#"{"key_gap_dp":99,"key_text_percent":999}"#).unwrap();
        assert_eq!((s.key_gap_dp, s.key_text_percent), (10, 150));
        // stary soubor bez poli
        let s = Settings::from_json(r#"{"theme":"light"}"#).unwrap();
        assert_eq!((s.glow_style.as_str(), s.key_gap_dp, s.key_text_percent), ("blink", 5, 100));
    }

    #[test]
    fn row_gap_default_derived_and_clamped() {
        let s = Settings::default();
        assert_eq!((s.key_gap_dp, s.row_gap_dp), (5, 8));
        // stary soubor jen s key_gap_dp -> odvozeno 1,6x
        let s = Settings::from_json(r#"{"key_gap_dp":5}"#).unwrap();
        assert_eq!(s.row_gap_dp, 8);
        let s = Settings::from_json(r#"{"key_gap_dp":10}"#).unwrap();
        assert_eq!(s.row_gap_dp, 16);
        let s = Settings::from_json(r#"{"key_gap_dp":1}"#).unwrap();
        assert_eq!(s.row_gap_dp, 2);
        // stary soubor bez poli
        let s = Settings::from_json(r#"{"theme":"light"}"#).unwrap();
        assert_eq!(s.row_gap_dp, 8);
        // explicitni hodnota zustane
        let s = Settings::from_json(r#"{"key_gap_dp":5,"row_gap_dp":3}"#).unwrap();
        assert_eq!((s.key_gap_dp, s.row_gap_dp), (5, 3));
        // mimo rozsah
        let s = Settings::from_json(r#"{"row_gap_dp":99}"#).unwrap();
        assert_eq!(s.row_gap_dp, 16);
        let s = Settings::from_json(r#"{"row_gap_dp":0}"#).unwrap();
        assert_eq!(s.row_gap_dp, 2);
        // round-trip
        let s2 = Settings::from_json(&s.to_json()).unwrap();
        assert_eq!(s2.row_gap_dp, 2);
    }

    #[test]
    fn popup_and_alts_colors() {
        let s = Settings::default();
        assert_eq!((s.popup_color.as_str(), s.alts_color.as_str()), ("", ""));
        let s = Settings::from_json(r##"{"popup_color":"#ff8800","alts_color":"red"}"##).unwrap();
        assert_eq!((s.popup_color.as_str(), s.alts_color.as_str()), ("#FF8800", ""));
        let s = Settings::from_json(r##"{"popup_color":"","alts_color":"#12"}"##).unwrap();
        assert_eq!((s.popup_color.as_str(), s.alts_color.as_str()), ("", ""));
        let s = Settings::from_json(r#"{"theme":"light"}"#).unwrap();
        assert_eq!((s.popup_color.as_str(), s.alts_color.as_str()), ("", ""));
    }

    #[test]
    fn divider_and_swap_defaults() {
        let s = Settings::default();
        assert!(s.number_divider && !s.swap_mode_enter && !s.swap_shift_delete);
        let s = Settings::from_json(r#"{"theme":"light"}"#).unwrap();
        assert!(s.number_divider && !s.swap_mode_enter && !s.swap_shift_delete);
        let s = Settings::from_json(r#"{"number_divider":false,"swap_mode_enter":true,"swap_shift_delete":true}"#).unwrap();
        assert!(!s.number_divider && s.swap_mode_enter && s.swap_shift_delete);
    }

    #[test]
    fn hint_xy() {
        let s = Settings::default();
        assert_eq!((s.key_hint_x, s.key_hint_y), (1.0, 0.0));
        // jen stary key_hint_pos -> migrace
        let s = Settings::from_json(r#"{"key_hint_pos":"bottom_center"}"#).unwrap();
        assert_eq!((s.key_hint_x, s.key_hint_y), (0.5, 1.0));
        let s = Settings::from_json(r#"{"key_hint_pos":"top_left"}"#).unwrap();
        assert_eq!((s.key_hint_x, s.key_hint_y), (0.0, 0.0));
        let s = Settings::from_json(r#"{"theme":"light"}"#).unwrap();
        assert_eq!((s.key_hint_x, s.key_hint_y), (1.0, 0.0));
        // explicitni hodnoty zustanou
        let s = Settings::from_json(r#"{"key_hint_pos":"bottom_left","key_hint_x":0.3,"key_hint_y":0.7}"#).unwrap();
        assert_eq!((s.key_hint_x, s.key_hint_y), (0.3, 0.7));
        // mimo rozsah -> zpet na stary key_hint_pos
        let s = Settings::from_json(r#"{"key_hint_pos":"bottom_right","key_hint_x":2.5,"key_hint_y":0.2}"#).unwrap();
        assert_eq!((s.key_hint_x, s.key_hint_y), (1.0, 1.0));
        let s = Settings::from_json(r#"{"key_hint_pos":"bottom_right","key_hint_x":0.5,"key_hint_y":-1.5}"#).unwrap();
        assert_eq!((s.key_hint_x, s.key_hint_y), (1.0, 1.0));
        // round-trip pres JSON
        let s2 = Settings::from_json(&s.to_json()).unwrap();
        assert_eq!((s2.key_hint_x, s2.key_hint_y), (1.0, 1.0));
    }

    #[test]
    fn hint_xy_extended_range() {
        // za okrajem klavesy az -1..2 (vcetne mezi)
        let s = Settings::from_json(r#"{"key_hint_x":1.5,"key_hint_y":-0.8}"#).unwrap();
        assert_eq!((s.key_hint_x, s.key_hint_y), (1.5, -0.8));
        let s = Settings::from_json(r#"{"key_hint_x":-1.0,"key_hint_y":2.0}"#).unwrap();
        assert_eq!((s.key_hint_x, s.key_hint_y), (-1.0, 2.0));
        let s2 = Settings::from_json(&s.to_json()).unwrap();
        assert_eq!((s2.key_hint_x, s2.key_hint_y), (-1.0, 2.0));
        // -1 uz neni "nezadano": chybejici hodnota se stale migruje ze stareho key_hint_pos
        let s = Settings::from_json(r#"{"key_hint_pos":"bottom_left"}"#).unwrap();
        assert_eq!((s.key_hint_x, s.key_hint_y), (0.0, 1.0));
    }

    #[test]
    fn hint_look_and_tap_effect() {
        let s = Settings::default();
        assert_eq!((s.hint_color.as_str(), s.hint_percent, s.tap_effect.as_str()), ("", 100, "neon"));
        let s = Settings::from_json(r##"{"hint_color":"#ff8800","hint_percent":150,"tap_effect":"vanish"}"##).unwrap();
        assert_eq!((s.hint_color.as_str(), s.hint_percent, s.tap_effect.as_str()), ("#FF8800", 150, "glitch"));
        let s = Settings::from_json(r#"{"hint_color":"x","hint_percent":10,"tap_effect":"boom"}"#).unwrap();
        assert_eq!((s.hint_color.as_str(), s.hint_percent, s.tap_effect.as_str()), ("", 60, "neon"));
        let s = Settings::from_json(r#"{"hint_percent":999}"#).unwrap();
        assert_eq!(s.hint_percent, 180);
    }

    #[test]
    fn bottom_keys_validation() {
        let s = Settings::default();
        assert_eq!((s.emoji_key.as_str(), s.macro_key.as_str()), ("left", "left"));
        let s = Settings::from_json(r#"{"emoji_key":"right","macro_key":"hidden"}"#).unwrap();
        assert_eq!((s.emoji_key.as_str(), s.macro_key.as_str()), ("right", "hidden"));
        let s = Settings::from_json(r#"{"emoji_key":"middle","macro_key":""}"#).unwrap();
        assert_eq!((s.emoji_key.as_str(), s.macro_key.as_str()), ("left", "left"));
        // stary soubor bez poli
        let s = Settings::from_json(r#"{"theme":"light"}"#).unwrap();
        assert_eq!((s.emoji_key.as_str(), s.macro_key.as_str()), ("left", "left"));
    }

    #[test]
    fn language_cycle() {
        let mut s = Settings::default();
        s.cycle_language();
        assert_eq!((s.language.as_str(), s.layout.as_str()), ("cs", "qwertz"));
        s.cycle_language();
        assert_eq!((s.language.as_str(), s.layout.as_str()), ("en", "qwerty"));
        s.cycle_language();
        assert_eq!(s.language, "cs+en");
        assert!(s.uses_lang("cs") && s.uses_lang("en"));
    }

    #[test]
    fn extra_colors_and_new_styles() {
        let s = Settings::default();
        assert_eq!((s.digit_text_color.as_str(), s.digit_key_color.as_str()), ("", ""));
        assert_eq!((s.key_color2.as_str(), s.background_color2.as_str()), ("", ""));
        assert_eq!(
            (s.paint_color1.as_str(), s.paint_color2.as_str(), s.paint_color3.as_str()),
            ("#0080FF", "#FF5100", "#6A02E8")
        );
        let s = Settings::from_json(
            r##"{"digit_text_color":"#ff0000","digit_key_color":"x","key_color2":"#00ff00",
                "background_color2":"#12","paint_color1":"#abcdef","paint_color2":"","paint_color3":"bad",
                "tap_effect":"paint","glow_style":"fly"}"##,
        )
        .unwrap();
        assert_eq!((s.digit_text_color.as_str(), s.digit_key_color.as_str()), ("#FF0000", ""));
        assert_eq!((s.key_color2.as_str(), s.background_color2.as_str()), ("#00FF00", ""));
        assert_eq!(
            (s.paint_color1.as_str(), s.paint_color2.as_str(), s.paint_color3.as_str()),
            ("#ABCDEF", "#FF5100", "#6A02E8")
        );
        assert_eq!((s.tap_effect.as_str(), s.glow_style.as_str()), ("paint", "fly"));
        let back = Settings::from_json(&s.to_json()).unwrap();
        assert_eq!(back, s);
        // stary soubor bez poli
        let old = Settings::from_json(r#"{"theme":"light"}"#).unwrap();
        assert_eq!(old.key_color2, "");
        assert_eq!(old.paint_color2, "#FF5100");
    }

    #[test]
    fn font_weights_and_hover_color() {
        let s = Settings::default();
        assert_eq!((s.label_weight, s.digit_weight, s.hover_text_color.as_str()), (400, 400, ""));
        let s = Settings::from_json(r##"{"label_weight":640,"digit_weight":349,"hover_text_color":"#00ff00"}"##).unwrap();
        assert_eq!((s.label_weight, s.digit_weight, s.hover_text_color.as_str()), (600, 300, "#00FF00"));
        let s = Settings::from_json(r##"{"label_weight":0,"digit_weight":4000000000,"hover_text_color":"bad"}"##).unwrap();
        assert_eq!((s.label_weight, s.digit_weight, s.hover_text_color.as_str()), (100, 900, ""));
        let s = Settings::from_json(r#"{"theme":"light"}"#).unwrap();
        assert_eq!((s.label_weight, s.digit_weight), (400, 400));
        let back = Settings::from_json(&s.to_json()).unwrap();
        assert_eq!(back, s);
    }

    #[test]
    fn label_position_text_colors_and_key_macros() {
        let s = Settings::default();
        assert_eq!((s.key_label_x, s.key_label_y), (0.5, 0.5));
        assert_eq!((s.passed_text_color.as_str(), s.tap_text_color.as_str()), ("", ""));
        assert!(s.key_macros.is_empty());
        let s = Settings::from_json(r##"{"key_label_x":0.2,"key_label_y":1.7,"passed_text_color":"#ff0000","tap_text_color":"bad"}"##).unwrap();
        assert_eq!((s.key_label_x, s.key_label_y), (0.2, 1.0));
        assert_eq!((s.passed_text_color.as_str(), s.tap_text_color.as_str()), ("#FF0000", ""));
        let s = Settings::from_json(r#"{"key_label_x":-3.0}"#).unwrap();
        assert_eq!((s.key_label_x, s.key_label_y), (0.0, 0.5));
        let s = Settings::from_json(r#"{"key_macros":{"a":3,"bb":4,"c":0," ":5,"d":7}}"#).unwrap();
        let keys: Vec<(&str, u32)> = s.key_macros.iter().map(|(k, v)| (k.as_str(), *v)).collect();
        assert_eq!(keys, vec![("a", 3), ("d", 7)]);
        let many: BTreeMap<String, u32> = (0..40u32).map(|i| (char::from_u32(0x100 + i).unwrap().to_string(), i + 1)).collect();
        let mut s = Settings { key_macros: many, ..Settings::default() };
        s.validate();
        assert_eq!(s.key_macros.len(), MAX_KEY_MACROS);
        let back = Settings::from_json(&s.to_json()).unwrap();
        assert_eq!(back, s);
    }

    #[test]
    fn vanish_effect_migrates_to_glitch() {
        let s = Settings::from_json(r#"{"tap_effect":"vanish"}"#).unwrap();
        assert_eq!(s.tap_effect, "glitch");
        let s = Settings::from_json(r#"{"tap_effect":"glitch"}"#).unwrap();
        assert_eq!(s.tap_effect, "glitch");
    }
}
