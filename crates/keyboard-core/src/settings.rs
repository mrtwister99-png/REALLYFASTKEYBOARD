//! Nastaveni klavesnice. Jediny zdroj pravdy: `settings.json` v app files dir.
//! Kotlin (SettingsActivity) i Rust ctou a zapisuji stejny soubor pres tento modul.

use serde::{Deserialize, Serialize};

pub const LANGUAGES: [&str; 3] = ["cs", "en", "cs+en"];
pub const LAYOUTS: [&str; 2] = ["qwertz", "qwerty"];
pub const THEMES: [&str; 4] = ["dark", "light", "oled_black", "custom"];

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct Settings {
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
    // --- gesta (kazde lze vypnout) ---
    pub gesture_delete_word: bool,
    pub gesture_space_cursor: bool,
    pub gesture_swipe_up_caps: bool,
    pub gesture_swipe_down_hide: bool,
    pub gesture_pinch_resize: bool,
    // --- schranka ---
    pub clipboard_enabled: bool,
    pub clipboard_ttl_minutes: u32,
}

impl Default for Settings {
    fn default() -> Self {
        Settings {
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
            accent_color: "#F59E0B".into(),
            show_number_row: true,
            show_suggestions: true,
            auto_capitalize: true,
            double_space_to_period: true,
            long_press_delay_ms: 250,
            custom_background: "#111113".into(),
            custom_key: "#26262B".into(),
            custom_text: "#F4F4F5".into(),
            key_popup: true,
            key_hints: true,
            key_shadow: true,
            key_radius_dp: 10,
            animations: true,
            swipe_trail: true,
            gesture_delete_word: true,
            gesture_space_cursor: true,
            gesture_swipe_up_caps: true,
            gesture_swipe_down_hide: true,
            gesture_pinch_resize: true,
            clipboard_enabled: true,
            clipboard_ttl_minutes: 60,
        }
    }
}

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
        fix_color(&mut self.accent_color, &d.accent_color);
        fix_color(&mut self.custom_background, &d.custom_background);
        fix_color(&mut self.custom_key, &d.custom_key);
        fix_color(&mut self.custom_text, &d.custom_text);
    }

    /// Nacte nastaveni z JSON. Chybejici pole dostanou vychozi hodnoty,
    /// neplatny JSON vraci `None`.
    pub fn from_json(json: &str) -> Option<Settings> {
        let mut s: Settings = serde_json::from_str(json).ok()?;
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
        assert_eq!(s.accent_color, "#F59E0B");
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
        assert_eq!(s.accent_color, "#F59E0B");
        assert!(Settings::from_json("not json").is_none());
    }

    #[test]
    fn appearance_defaults_and_limits() {
        let s = Settings::default();
        assert!(s.key_popup && s.key_hints && s.key_shadow && s.animations && s.swipe_trail);
        assert_eq!(s.key_radius_dp, 10);
        let s = Settings::from_json(r#"{"key_radius_dp":99,"animations":false}"#).unwrap();
        assert_eq!(s.key_radius_dp, 22);
        assert!(!s.animations);
        // stary settings.json z Faze 1 (bez novych poli) se nacte s vychozimi hodnotami
        let old = Settings::from_json(r#"{"theme":"light"}"#).unwrap();
        assert!(old.key_popup && old.swipe_trail);
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
}
