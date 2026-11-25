//! Nastaveni klavesnice. Jediny zdroj pravdy: `settings.json` v app files dir.
//! Kotlin (SettingsActivity) i Rust ctou a zapisuji stejny soubor pres tento modul.

use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
use std::sync::OnceLock;

pub const LANGUAGES: [&str; 3] = ["cs", "en", "cs+en"];
pub const LAYOUTS: [&str; 2] = ["qwertz", "qwerty"];
pub const KEY_PLACES: [&str; 3] = ["left", "right", "hidden"];
pub const THEMES: [&str; 4] = ["dark", "light", "oled_black", "custom"];
pub const GLOW_STYLES: [&str; 4] = ["neon", "paint", "ghost", "flip"];
pub const TAP_EFFECTS: [&str; 8] = ["neon", "glitch", "paint", "ghost", "orbit", "pop", "cut", "slide"];
/// Vzhled stopy za prstem pri tahu: cara, nebo horici cara (ohen a dohorivajici stopy).
pub const TRAIL_STYLES: [&str; 4] = ["line", "dashed", "fire", "pixel"];
/// Zacatek prechodu druhych barev: odkud (shora, zdola, zleva, zprava) se prechod rozbiha.
pub const GRAD_DIRS: [&str; 4] = ["top", "bottom", "left", "right"];
/// Zvlastni hodnota barvy "duha": povolena jen u druhych barev prechodu (klavesy, pozadi, znaky, obrys),
/// u druhe barvy efektu po klepnuti a u barvy pismen po tahu.
pub const RAINBOW: &str = "rainbow";
/// Pocet slotu vlastnich stylu.
pub const STYLE_SLOTS: usize = 5;
/// Klice nastaveni, ktere tvori vzhled (ulozi se do slotu stylu). Jazyk, rozlozeni, cislicova rada,
/// mapy klaves, makra, gesta, spodni rada ani chovani psani sem nepatri.
pub const STYLE_KEYS: &[&str] = &[
    "theme", "accent_color", "custom_background", "custom_key", "custom_text",
    "key_height_dp", "key_width_dp", "key_radius_dp", "key_text_percent", "key_gap_dp", "row_gap_dp", "key_font", "trail_width_dp",
    "key_label_x", "key_label_y", "key_hint_x", "key_hint_y", "key_hint_pos",
    "key_popup", "key_hints", "key_shadow", "animations", "swipe_trail", "swipe_glow", "key_glow",
    "hint_color", "hint_percent", "tap_effect", "neon_color", "glow_color", "glow_style",
    "popup_color", "alts_color", "number_divider", "trail_color", "trail_style", "text_color2",
    "digit_text_color", "digit_key_color", "key_color2", "background_color2",
    "digit_key_color2", "digit_text_color2", "digit_outline_color2",
    "bg_grad_dir", "key_grad_dir", "digit_key_grad_dir", "text_grad_dir", "digit_text_grad_dir", "outline_grad_dir", "digit_outline_grad_dir",
    "paint_color1", "paint_color2", "paint_color3",
    "passed_text_color", "tap_text_color", "hover_text_color", "label_weight", "digit_weight",
    "neon_color2", "outline_color", "outline_color2", "outline_tap_color", "outline_hover_color",
    "outline_passed_color", "outline_width_dp", "char_outline",
    "special_outline", "trail_color2", "glow_color2", "tap_text_color2", "outline_tap_color2",
];
/// Jeden slot stylu: klic nastaveni -> hodnota (jen klice ze STYLE_KEYS).
pub type StyleSlot = serde_json::Map<String, serde_json::Value>;
/// Vychozi sloty 2-5: jen jedna barva (accent; neon a barva pismen po tahu jsou stejne), zbytek jako vychozi vzhled.
const STYLE_SLOT_ACCENTS: [&str; 4] = ["#60A5FA", "#34D399", "#2563EB", "#FB7185"];
/// Drivejsi vychozi sloty 2-5 (cele barvy hotovych stylu): nezmenene se pri nacteni nahradi novymi vychozimi.
const LEGACY_SLOT_COLORS: [(&str, &str, &str, &str); 4] = [
    ("#0B1220", "#1B2740", "#E6EDF7", "#60A5FA"),
    ("#0F1A14", "#1E3328", "#E8F3EC", "#34D399"),
    ("#E6E8EC", "#FFFFFF", "#15151A", "#2563EB"),
    ("#1A1114", "#33202A", "#F8E9EE", "#FB7185"),
];

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
    pub key_width_dp: u32,
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
    /// Efekt po klepnuti na klavesu: "neon", "glitch", "paint" nebo "ghost" (dozvuk). Zapina ho `key_glow`.
    pub tap_effect: String,
    /// Barva neonu po klepnuti.
    pub neon_color: String,
    /// Druha barva efektu po klepnuti ("" = jen jedna barva, "rainbow" = nahodny odstin duhy):
    /// neon a glitch stridaji obe barvy, efekt "paint" ma vlastni barvy.
    pub neon_color2: String,
    /// Barva pismen po uspesnem tahu.
    pub glow_color: String,
    /// Animace pismen po uspesnem tahu: "blink" (postupne zablikani) nebo "fill" (vybarveni klaves).
    pub glow_style: String,
    /// Vzhled stopy za prstem: "line" (cara) nebo "fire" (horici cara se stopami, ktere dohori).
    pub trail_style: String,
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
    /// Barva cary za prstem pri swipe ("" = automaticka).
    pub trail_color: String,
    /// Druha barva znaku: prechod shora (barva textu) dolu (tato barva); "" = vypnuto.
    pub text_color2: String,
    /// Barva znaku cislicovych klaves ("" = automaticka).
    pub digit_text_color: String,
    /// Barva cislicovych klaves ("" = automaticka).
    pub digit_key_color: String,
    /// Druhe barvy cislicovych klaves, jejich znaku a obrysu: prechod zleva doprava; "" = vypnuto.
    pub digit_key_color2: String,
    pub digit_text_color2: String,
    pub digit_outline_color2: String,
    /// Zacatek prechodu druhe barvy pro pozadi, klavesy, klavesy cisel, znaky, znaky cisel, obrysy a obrysy cisel.
    pub bg_grad_dir: String,
    pub key_grad_dir: String,
    pub digit_key_grad_dir: String,
    pub text_grad_dir: String,
    pub digit_text_grad_dir: String,
    pub outline_grad_dir: String,
    pub digit_outline_grad_dir: String,
    /// Druha barva klaves: prechod zleva (barva klavesy) doprava (barva klavesy) dolu (tato barva); "" = vypnuto.
    pub key_color2: String,
    /// Druha barva pozadi: prechod zleva (pozadi) doprava (tato barva); "" = vypnuto.
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
    /// Obrys klaves ("" = bez staleho obrysu).
    pub outline_color: String,
    /// Druha barva obrysu: prechod shora (barva obrysu) dolu (tato barva); "" = vypnuto, "rainbow" = duha.
    pub outline_color2: String,
    /// Barva obrysu po klepnuti ("" = vypnuto).
    pub outline_tap_color: String,
    /// Barva obrysu klavesy pod prstem ("" = vypnuto).
    pub outline_hover_color: String,
    /// Barva obrysu klaves, pres ktere prst prejel pri tahu ("" = vypnuto).
    pub outline_passed_color: String,
    /// Tloustka obrysu v dp (1..6).
    pub outline_width_dp: u32,
    pub char_outline: bool,
    pub trail_width_dp: u32,
    /// Pismo klaves: "system" nebo nazev souboru pisma z assets/fonts (bez cesty).
    pub key_font: String,
    /// Obrys specialnich klaves (Shift, ?123, Backspace) v barve akcentu.
    pub special_outline: bool,
    /// Druha barva stopy za prstem: konec stopy ("" = jen jedna barva).
    pub trail_color2: String,
    /// Druha barva pismen po uspesnem tahu: pismena plynule prechazeji z `glow_color` do teto barvy ("" = vypnuto).
    pub glow_color2: String,
    /// Druha barva znaku po klepnuti: klepnuti stridaji obe barvy ("" = vypnuto).
    pub tap_text_color2: String,
    /// Druha barva obrysu po klepnuti: klepnuti stridaji obe barvy ("" = vypnuto).
    pub outline_tap_color2: String,
    /// Makro prirazene primo klavese: puvodni znak pozice -> id makra.
    pub key_macros: BTreeMap<String, u32>,
    /// Makro prirazene podrzeni klavesy (klavesa si drzi znak i vzhled): puvodni znak pozice -> id makra.
    pub key_macros_hold: BTreeMap<String, u32>,
    /// Obrazek makra (zvoleny pri tvorbe makra): id makra -> 0 (trol), 1 (chocho), 2 (dollo) nebo 3 (maskot); plati vsude, kde se makro na klavese ukaze (klik i podrzeni).
    pub macro_faces: BTreeMap<String, u32>,
    /// Vlastni styly (sloty 1-5): kazdy je objekt s klici STYLE_KEYS. Vzdy presne STYLE_SLOTS platnych slotu
    /// (chybejici nebo neplatne se doplni vychozimi).
    pub style_slots: serde_json::Value,
}

impl Settings {
    /// Vychozi hodnoty bez slotu stylu (sloty se z nich pocitaji, proto zvlast kvuli rekurzi).
    fn base_default() -> Settings {
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
            key_width_dp: 0,
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
            neon_color2: String::new(),
            glow_color: DEFAULT_ACCENT.into(),
            glow_style: "neon".into(),
            trail_style: "line".into(),
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
            trail_color: String::new(),
            text_color2: String::new(),
            digit_text_color: String::new(),
            digit_key_color: String::new(),
            key_color2: String::new(),
            digit_key_color2: String::new(),
            digit_text_color2: String::new(),
            digit_outline_color2: String::new(),
            bg_grad_dir: "top".into(),
            key_grad_dir: "top".into(),
            digit_key_grad_dir: "top".into(),
            text_grad_dir: "top".into(),
            digit_text_grad_dir: "top".into(),
            outline_grad_dir: "top".into(),
            digit_outline_grad_dir: "top".into(),
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
            outline_color: String::new(),
            outline_color2: String::new(),
            outline_tap_color: String::new(),
            outline_hover_color: String::new(),
            outline_passed_color: String::new(),
            outline_width_dp: 2,
            char_outline: false,
            trail_width_dp: 4,
            key_font: "system".into(),
            special_outline: true,
            trail_color2: String::new(),
            glow_color2: String::new(),
            tap_text_color2: String::new(),
            outline_tap_color2: String::new(),
            key_macros: BTreeMap::new(),
            key_macros_hold: BTreeMap::new(),
            macro_faces: BTreeMap::new(),
            style_slots: serde_json::Value::Array(Vec::new()),
        }
    }
}

impl Default for Settings {
    fn default() -> Self {
        let mut s = Settings::base_default();
        s.style_slots = default_slots_value();
        s
    }
}

/// Vychozi sloty (spocitaji se jednou): 1 = vychozi vzhled, 2-5 = vychozi vzhled s jednou jinou barvou.
fn default_style_slots() -> &'static [StyleSlot] {
    static SLOTS: OnceLock<Vec<StyleSlot>> = OnceLock::new();
    SLOTS.get_or_init(|| {
        let mut base = Settings::base_default();
        base.validate_fields();
        let mut out = vec![base.style_snapshot()];
        for accent in STYLE_SLOT_ACCENTS {
            let mut s = Settings::base_default();
            s.accent_color = accent.into();
            s.neon_color = accent.into();
            s.glow_color = accent.into();
            s.validate_fields();
            out.push(s.style_snapshot());
        }
        out
    })
}

/// Drivejsi vychozi sloty 2-5 (index 0 = slot 2); jen pro rozpoznani nezmenenych a jejich nahrazeni.
fn legacy_style_slots() -> &'static [StyleSlot] {
    static SLOTS: OnceLock<Vec<StyleSlot>> = OnceLock::new();
    SLOTS.get_or_init(|| {
        let mut out = Vec::new();
        for (bg, key, text, accent) in LEGACY_SLOT_COLORS {
            let mut s = Settings::base_default();
            s.theme = "custom".into();
            s.custom_background = bg.into();
            s.custom_key = key.into();
            s.custom_text = text.into();
            s.accent_color = accent.into();
            s.neon_color = accent.into();
            s.glow_color = accent.into();
            s.validate_fields();
            out.push(s.style_snapshot());
        }
        out
    })
}

fn default_slots_value() -> serde_json::Value {
    serde_json::Value::Array(default_style_slots().iter().cloned().map(serde_json::Value::Object).collect())
}

/// Presne STYLE_SLOTS slotu; kazdy = vychozi slot prepsany ulozenymi hodnotami, zvalidovany, jen klice STYLE_KEYS.
fn normalize_slots(v: &serde_json::Value) -> serde_json::Value {
    let defaults = default_style_slots();
    let given = v.as_array();
    serde_json::Value::Array(
        (0..STYLE_SLOTS)
            .map(|i| {
                let d = &defaults[i];
                let Some(raw) = given.and_then(|a| a.get(i)).and_then(|s| s.as_object()) else {
                    return serde_json::Value::Object(d.clone());
                };
                match Settings::base_default().overlay(d).and_then(|t| t.overlay(raw)) {
                    Some(mut t) => {
                        t.validate_fields();
                        let snap = t.style_snapshot();
                        // Nezmeneny drivejsi vychozi slot (cele barvy) se nahradi novym jednobarevnym.
                        if i >= 1 && legacy_style_slots().get(i - 1) == Some(&snap) {
                            serde_json::Value::Object(d.clone())
                        } else {
                            serde_json::Value::Object(snap)
                        }
                    }
                    None => serde_json::Value::Object(d.clone()),
                }
            })
            .collect(),
    )
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
/// Pocet obrazku makra (trol, chocho, dollo, maskot); musi sedet s MacroFaces.COUNT v Kotlinu.
pub const MACRO_FACES: u32 = 4;
/// Nejvyse tolik maker ma v nastaveni zvoleny obrazek.
pub const MAX_MACRO_FACES: usize = 256;
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

/// Jako `fix_optional_color`, navic povoli duhu (bez ohledu na velikost pismen).
fn fix_grad_dir(value: &mut String) {
    if !GRAD_DIRS.contains(&value.as_str()) {
        *value = "top".into();
    }
}

fn fix_optional_color_or_rainbow(value: &mut String) {
    if value.eq_ignore_ascii_case(RAINBOW) {
        *value = RAINBOW.to_string();
    } else {
        fix_optional_color(value);
    }
}

/// Jako `fix_color`, navic povoli duhu.
fn fix_color_or_rainbow(value: &mut String, default: &str) {
    if value.eq_ignore_ascii_case(RAINBOW) {
        *value = RAINBOW.to_string();
    } else {
        fix_color(value, default);
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
    /// Orizne hodnoty do povolenych rozsahu a nahradi neplatne volby vychozimi (vcetne slotu stylu).
    pub fn validate(&mut self) {
        self.validate_fields();
        self.style_slots = normalize_slots(&self.style_slots);
    }

    /// Soucasny vzhled jako slot stylu (jen klice STYLE_KEYS).
    pub fn style_snapshot(&self) -> StyleSlot {
        let mut out = StyleSlot::new();
        if let Ok(serde_json::Value::Object(all)) = serde_json::to_value(self) {
            for k in STYLE_KEYS {
                if let Some(v) = all.get(*k) {
                    out.insert((*k).to_string(), v.clone());
                }
            }
        }
        out
    }

    /// Kopie s hodnotami ze slotu (jen klice STYLE_KEYS), bez validace; None, kdyz hodnota nema spravny typ.
    fn overlay(&self, slot: &StyleSlot) -> Option<Settings> {
        let mut all = match serde_json::to_value(self) {
            Ok(serde_json::Value::Object(m)) => m,
            _ => return None,
        };
        for k in STYLE_KEYS {
            if let Some(v) = slot.get(*k) {
                all.insert((*k).to_string(), v.clone());
            }
        }
        serde_json::from_value(serde_json::Value::Object(all)).ok()
    }

    /// Nastaveni s vzhledem ze slotu; ostatni nastaveni (jazyk, mapy, makra...) i sloty zustanou.
    pub fn with_style(&self, slot: &StyleSlot) -> Settings {
        let mut s = self.overlay(slot).unwrap_or_else(|| self.clone());
        s.validate();
        s
    }

    fn validate_fields(&mut self) {
        let d = Settings::base_default();
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
        self.key_gap_dp = self.key_gap_dp.clamp(0, 10);
        if self.key_width_dp != 0 { self.key_width_dp = self.key_width_dp.clamp(24, 48); }
        self.trail_width_dp = self.trail_width_dp.clamp(1, 12);
        // Nazev pisma: jen jednoduchy nazev souboru (zadna cesta), jinak systemove.
        if self.key_font.is_empty() || self.key_font.len() > 64 || self.key_font.contains(['/', '\\'])|| self.key_font.contains("..") {
            self.key_font = "system".into();
        }
        if self.row_gap_dp < 0 {
            self.row_gap_dp = (self.key_gap_dp as f32 * 1.6).round() as i32;
        }
        self.row_gap_dp = self.row_gap_dp.clamp(0, 16);
        self.key_text_percent = self.key_text_percent.clamp(70, 200);
        match self.glow_style.as_str() { "blink" => self.glow_style = "neon".into(), "fill" => self.glow_style = "paint".into(), "fly" => self.glow_style = "ghost".into(), _ => {} }
        if !GLOW_STYLES.contains(&self.glow_style.as_str()) {
            self.glow_style = d.glow_style.clone();
        }
        if !TRAIL_STYLES.contains(&self.trail_style.as_str()) {
            self.trail_style = d.trail_style.clone();
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
        self.key_macros_hold = std::mem::take(&mut self.key_macros_hold)
            .into_iter()
            .filter(|(k, v)| single_char(k).is_some() && *v > 0)
            .take(MAX_KEY_MACROS)
            .collect();
        self.macro_faces = std::mem::take(&mut self.macro_faces)
            .into_iter()
            .filter(|(k, v)| k.parse::<u64>().is_ok() && *v < MACRO_FACES)
            .take(MAX_MACRO_FACES)
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
        fix_optional_color(&mut self.trail_color);
        fix_optional_color_or_rainbow(&mut self.text_color2);
        fix_optional_color(&mut self.digit_text_color);
        fix_optional_color(&mut self.digit_key_color);
        fix_optional_color_or_rainbow(&mut self.digit_key_color2);
        fix_optional_color_or_rainbow(&mut self.digit_text_color2);
        fix_optional_color_or_rainbow(&mut self.digit_outline_color2);
        fix_grad_dir(&mut self.bg_grad_dir);
        fix_grad_dir(&mut self.key_grad_dir);
        fix_grad_dir(&mut self.digit_key_grad_dir);
        fix_grad_dir(&mut self.text_grad_dir);
        fix_grad_dir(&mut self.digit_text_grad_dir);
        fix_grad_dir(&mut self.outline_grad_dir);
        fix_grad_dir(&mut self.digit_outline_grad_dir);
        fix_optional_color_or_rainbow(&mut self.key_color2);
        fix_optional_color_or_rainbow(&mut self.background_color2);
        fix_color(&mut self.paint_color1, &d.paint_color1);
        fix_color(&mut self.paint_color2, &d.paint_color2);
        fix_color(&mut self.paint_color3, &d.paint_color3);
        fix_color(&mut self.neon_color, &d.neon_color);
        fix_optional_color_or_rainbow(&mut self.neon_color2);
        fix_optional_color(&mut self.outline_color);
        fix_optional_color_or_rainbow(&mut self.outline_color2);
        fix_optional_color(&mut self.outline_tap_color);
        fix_optional_color(&mut self.outline_hover_color);
        fix_optional_color(&mut self.outline_passed_color);
        self.outline_width_dp = self.outline_width_dp.clamp(1, 6);
        fix_optional_color(&mut self.trail_color2);
        fix_optional_color(&mut self.glow_color2);
        fix_optional_color(&mut self.tap_text_color2);
        fix_optional_color(&mut self.outline_tap_color2);
        fix_color_or_rainbow(&mut self.glow_color, &d.glow_color);
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
        serde_json::to_string(self).unwrap_or_else(|_| "{}".to_string())
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
    fn second_digit_colors_and_gradient_directions() {
        let d = Settings::default();
        assert_eq!((d.digit_key_color2.as_str(), d.digit_text_color2.as_str(), d.digit_outline_color2.as_str()), ("", "", ""));
        let s = Settings::from_json(r##"{"digit_key_color2":"#ff0000","digit_text_color2":"rainbow","digit_outline_color2":"x"}"##).unwrap();
        assert_eq!((s.digit_key_color2.as_str(), s.digit_text_color2.as_str(), s.digit_outline_color2.as_str()), ("#FF0000", "rainbow", ""));
        assert_eq!((d.bg_grad_dir.as_str(), d.digit_outline_grad_dir.as_str()), ("top", "top"));
        let s = Settings::from_json(r#"{"bg_grad_dir":"left","key_grad_dir":"bottom","text_grad_dir":"right","outline_grad_dir":"diag"}"#).unwrap();
        assert_eq!((s.bg_grad_dir.as_str(), s.key_grad_dir.as_str(), s.text_grad_dir.as_str(), s.outline_grad_dir.as_str()), ("left", "bottom", "right", "top"));
        for k in ["digit_key_color2", "digit_text_color2", "digit_outline_color2", "bg_grad_dir", "key_grad_dir", "digit_key_grad_dir", "text_grad_dir", "digit_text_grad_dir", "outline_grad_dir", "digit_outline_grad_dir"] {
            assert!(STYLE_KEYS.contains(&k), "{k}");
        }
    }

    #[test]
    fn key_width_is_limited() {
        let s = Settings::from_json(r#"{"key_width_dp":120}"#).unwrap();
        assert_eq!(s.key_width_dp, 48);
        let s = Settings::from_json(r#"{"key_width_dp":5}"#).unwrap();
        assert_eq!(s.key_width_dp, 24);
        let s = Settings::from_json(r#"{"key_width_dp":0}"#).unwrap();
        assert_eq!(s.key_width_dp, 0);
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
    fn trail_and_second_text_color() {
        let s = Settings::default();
        assert_eq!((s.trail_color.as_str(), s.text_color2.as_str()), ("", ""));
        let s = Settings::from_json(r##"{"trail_color":"#ff8800","text_color2":"zzz"}"##).unwrap();
        assert_eq!((s.trail_color.as_str(), s.text_color2.as_str()), ("#FF8800", ""));
        assert_eq!(Settings::from_json(&s.to_json()).unwrap(), s);
    }

    #[test]
    fn key_glow_and_key_map_defaults() {
        let s = Settings::default();
        assert!(s.key_glow && s.tap_effect == "neon");
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
        assert_eq!((s.glow_style.as_str(), s.key_gap_dp, s.key_text_percent), ("neon", 5, 100));
        let s = Settings::from_json(r#"{"glow_style":"paint","key_gap_dp":7,"key_text_percent":120}"#).unwrap();
        assert_eq!((s.glow_style.as_str(), s.key_gap_dp, s.key_text_percent), ("paint", 7, 120));
        let s = Settings::from_json(r#"{"glow_style":"spin","key_gap_dp":0,"key_text_percent":10}"#).unwrap();
        assert_eq!((s.glow_style.as_str(), s.key_gap_dp, s.key_text_percent), ("neon", 0, 70));
        let s = Settings::from_json(r#"{"key_gap_dp":99,"key_text_percent":999}"#).unwrap();
        assert_eq!((s.key_gap_dp, s.key_text_percent), (10, 200));
        // stary soubor bez poli
        let s = Settings::from_json(r#"{"theme":"light"}"#).unwrap();
        assert_eq!((s.glow_style.as_str(), s.key_gap_dp, s.key_text_percent), ("neon", 5, 100));
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
        // 0 je platna hodnota (rady klaves bez mezery)
        let s = Settings::from_json(r#"{"row_gap_dp":0}"#).unwrap();
        assert_eq!(s.row_gap_dp, 0);
        // round-trip
        let s2 = Settings::from_json(&s.to_json()).unwrap();
        assert_eq!(s2.row_gap_dp, 0);
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
                "tap_effect":"paint","glow_style":"ghost"}"##,
        )
        .unwrap();
        assert_eq!((s.digit_text_color.as_str(), s.digit_key_color.as_str()), ("#FF0000", ""));
        assert_eq!((s.key_color2.as_str(), s.background_color2.as_str()), ("#00FF00", ""));
        assert_eq!(
            (s.paint_color1.as_str(), s.paint_color2.as_str(), s.paint_color3.as_str()),
            ("#ABCDEF", "#FF5100", "#6A02E8")
        );
        assert_eq!((s.tap_effect.as_str(), s.glow_style.as_str()), ("paint", "ghost"));
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
    fn hold_macros_and_macro_faces() {
        let s = Settings::default();
        assert!(s.key_macros_hold.is_empty() && s.macro_faces.is_empty());
        let s = Settings::from_json(r#"{"key_macros_hold":{"a":3,"bb":4,"c":0," ":5,"d":7},"key_macros":{"a":1}}"#).unwrap();
        let keys: Vec<(&str, u32)> = s.key_macros_hold.iter().map(|(k, v)| (k.as_str(), *v)).collect();
        assert_eq!(keys, vec![("a", 3), ("d", 7)]);
        assert_eq!(s.key_macros.len(), 1);
        // obrazek patri makru (klic = id makra): neciselne klice a hodnoty mimo rozsah se zahodi
        let s = Settings::from_json(r#"{"macro_faces":{"1":0,"2":1,"3":2,"4":3,"5":4,"x":1}}"#).unwrap();
        let faces: Vec<(&str, u32)> = s.macro_faces.iter().map(|(k, v)| (k.as_str(), *v)).collect();
        assert_eq!(faces, vec![("1", 0), ("2", 1), ("3", 2), ("4", 3)]);
        let many: BTreeMap<String, u32> = (0..40u32).map(|i| (char::from_u32(0x100 + i).unwrap().to_string(), i + 1)).collect();
        let faces: BTreeMap<String, u32> = (0..300u32).map(|i| (i.to_string(), i % 2)).collect();
        let mut s = Settings { key_macros_hold: many, macro_faces: faces, ..Settings::default() };
        s.validate();
        assert_eq!(s.key_macros_hold.len(), MAX_KEY_MACROS);
        assert_eq!(s.macro_faces.len(), MAX_MACRO_FACES);
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

    #[test]
    fn rainbow_second_colors_and_glow() {
        let s = Settings::from_json(
            r##"{"key_color2":"rainbow","background_color2":"RAINBOW","text_color2":"rainbow","glow_color":"Rainbow",
                "neon_color":"rainbow","hint_color":"rainbow"}"##,
        )
        .unwrap();
        assert_eq!(
            (s.key_color2.as_str(), s.background_color2.as_str(), s.text_color2.as_str(), s.glow_color.as_str()),
            ("rainbow", "rainbow", "rainbow", "rainbow")
        );
        // jinde duha neni povolena
        assert_eq!((s.neon_color.as_str(), s.hint_color.as_str()), (DEFAULT_ACCENT, ""));
        assert_eq!(Settings::from_json(&s.to_json()).unwrap(), s);
    }

    #[test]
    fn style_keys_exist_and_default_slots_are_full() {
        let all = serde_json::to_value(Settings::default()).unwrap();
        for k in STYLE_KEYS {
            assert!(all.get(*k).is_some(), "{k}");
        }
        let unique: std::collections::BTreeSet<_> = STYLE_KEYS.iter().collect();
        assert_eq!(unique.len(), STYLE_KEYS.len());
        let d = Settings::default();
        let slots = d.style_slots.as_array().unwrap();
        assert_eq!(slots.len(), STYLE_SLOTS);
        for s in slots {
            assert_eq!(s.as_object().unwrap().len(), STYLE_KEYS.len());
        }
        assert_eq!(slots[0]["theme"], "dark");
        // vychozi sloty 2-5: vychozi vzhled s jednou jinou barvou, bez druhych barev, zakladni efekt
        assert_eq!(slots[1]["accent_color"], "#60A5FA");
        for s in &slots[1..] {
            assert_eq!(s["theme"], "dark");
            assert_eq!(s["custom_background"], slots[0]["custom_background"]);
            assert_eq!(s["custom_key"], slots[0]["custom_key"]);
            assert_eq!(s["key_color2"], "");
            assert_eq!(s["tap_effect"], "neon");
            assert_eq!(s["neon_color"], s["accent_color"]);
        }
        // stary soubor bez slotu -> vychozi sloty
        assert_eq!(Settings::from_json(r#"{"theme":"light"}"#).unwrap().style_slots, d.style_slots);
    }

    #[test]
    fn untouched_legacy_slots_become_single_colour_and_saved_ones_stay() {
        let legacy: Vec<serde_json::Value> = std::iter::once(Settings::default().style_slots[0].clone())
            .chain(legacy_style_slots().iter().cloned().map(serde_json::Value::Object))
            .collect();
        let json = serde_json::json!({"theme": "dark", "style_slots": legacy});
        let s = Settings::from_json(&json.to_string()).unwrap();
        assert_eq!(s.style_slots, Settings::default().style_slots);
        // slot upraveny uzivatelem se nemeni
        let mut changed = legacy.clone();
        changed[2]["key_radius_dp"] = serde_json::json!(21);
        let json = serde_json::json!({"theme": "dark", "style_slots": changed});
        let s = Settings::from_json(&json.to_string()).unwrap();
        let slots = s.style_slots.as_array().unwrap();
        assert_eq!(slots[2]["key_radius_dp"], 21);
        assert_eq!(slots[2]["custom_background"], "#0F1A14");
        assert_eq!(slots[1], Settings::default().style_slots[1]);
    }

    #[test]
    fn style_slots_are_normalized() {
        let s = Settings::from_json(
            r##"{"theme":"light","style_slots":[{"key_height_dp":999,"theme":"pink","language":"en",
                "custom_key":"#abcdef","key_color2":"rainbow"},5,{"key_height_dp":"x"}]}"##,
        )
        .unwrap();
        assert_eq!(s.theme, "light");
        let slots = s.style_slots.as_array().unwrap();
        let dv = Settings::default().style_slots;
        let d = dv.as_array().unwrap();
        assert_eq!(slots.len(), STYLE_SLOTS);
        assert_ne!(slots[0]["key_height_dp"], 999);
        assert_eq!(slots[0]["theme"], "dark");
        assert_eq!(slots[0]["custom_key"], "#ABCDEF");
        assert_eq!(slots[0]["key_color2"], "rainbow");
        assert!(slots[0].get("language").is_none());
        assert_eq!(slots[0].as_object().unwrap().len(), STYLE_KEYS.len());
        assert_eq!(&slots[1..2], &d[1..2]);
        assert_eq!(&slots[3..], &d[3..]);
        assert_eq!(Settings::from_json(&s.to_json()).unwrap(), s);
        // nesmysl misto pole -> vychozi sloty, zbytek nastaveni zustane
        let s = Settings::from_json(r#"{"theme":"light","style_slots":"x"}"#).unwrap();
        assert_eq!((s.theme.as_str(), &s.style_slots), ("light", &Settings::default().style_slots));
    }

    #[test]
    fn neon_color2_and_outline() {
        let s = Settings::default();
        assert_eq!(s.neon_color2, "");
        assert_eq!(
            (s.outline_color.as_str(), s.outline_color2.as_str(), s.outline_tap_color.as_str(),
             s.outline_hover_color.as_str(), s.outline_passed_color.as_str(), s.outline_width_dp),
            ("", "", "", "", "", 2)
        );
        let s = Settings::from_json(
            r##"{"neon_color2":"Rainbow","outline_color":"#ff0000","outline_color2":"rainbow","outline_tap_color":"rainbow",
                "outline_hover_color":"#00ff00","outline_passed_color":"bad","outline_width_dp":99}"##,
        )
        .unwrap();
        assert_eq!(s.neon_color2, "rainbow");
        assert_eq!((s.outline_color.as_str(), s.outline_color2.as_str()), ("#FF0000", "rainbow"));
        assert_eq!(
            (s.outline_tap_color.as_str(), s.outline_hover_color.as_str(), s.outline_passed_color.as_str()),
            ("", "#00FF00", "")
        );
        assert_eq!(s.outline_width_dp, 6);
        assert_eq!(Settings::from_json(&s.to_json()).unwrap(), s);
        let s = Settings::from_json(r##"{"neon_color2":"#abcdef","outline_color":"rainbow","outline_width_dp":0}"##).unwrap();
        assert_eq!((s.neon_color2.as_str(), s.outline_color.as_str(), s.outline_width_dp), ("#ABCDEF", "", 1));
        let old = Settings::from_json(r#"{"theme":"light"}"#).unwrap();
        assert_eq!((old.neon_color2.as_str(), old.outline_color.as_str(), old.outline_width_dp), ("", "", 2));
    }

    #[test]
    fn outline_and_effect_color2_are_style_keys() {
        for k in ["neon_color2", "outline_color", "outline_color2", "outline_tap_color", "outline_hover_color",
                  "outline_passed_color", "outline_width_dp"] {
            assert!(STYLE_KEYS.contains(&k), "{k}");
        }
        let mut s = Settings::default();
        s.outline_color = "#123456".into();
        s.neon_color2 = "rainbow".into();
        let slot = s.style_snapshot();
        assert_eq!(slot["outline_color"], "#123456");
        let back = Settings::default().with_style(&slot);
        assert_eq!((back.outline_color.as_str(), back.neon_color2.as_str()), ("#123456", "rainbow"));
    }

    #[test]
    fn orbit_and_pop_tap_effects_are_valid() {
        for e in ["orbit", "pop"] {
            let s = Settings::from_json(&format!(r#"{{"tap_effect":"{e}"}}"#)).unwrap();
            assert_eq!(s.tap_effect, e);
            let back = Settings::from_json(&s.to_json()).unwrap();
            assert_eq!(back.tap_effect, e);
        }
    }

    #[test]
    fn trail_style_is_validated_and_part_of_styles() {
        assert_eq!(Settings::default().trail_style, "line");
        let s = Settings::from_json(r#"{"trail_style":"fire"}"#).unwrap();
        assert_eq!(s.trail_style, "fire");
        assert_eq!(Settings::from_json(&s.to_json()).unwrap().trail_style, "fire");
        // Zruseny styl a neznama hodnota se vrati na caru.
        for bad in ["memorocht", "zigzag"] {
            let s = Settings::from_json(&format!(r#"{{"trail_style":"{bad}"}}"#)).unwrap();
            assert_eq!(s.trail_style, "line");
        }
        assert!(STYLE_KEYS.contains(&"trail_style"));
    }

    #[test]
    fn removed_glow_styles_and_face_effect() {
        // Zrusene styly po tahu se vrati na neon, zachovane zustavaji.
        for old in ["cut", "slide", "glitch", "orbit", "pop"] {
            let s = Settings::from_json(&format!(r#"{{"glow_style":"{old}"}}"#)).unwrap();
            assert_eq!(s.glow_style, "neon");
        }
        for ok in ["neon", "paint", "ghost", "flip"] {
            let s = Settings::from_json(&format!(r#"{{"glow_style":"{ok}"}}"#)).unwrap();
            assert_eq!(s.glow_style, ok);
        }
        let s = Settings::from_json(r#"{"trail_style":"flip"}"#).unwrap();
        assert_eq!(s.trail_style, "line");
        // Zruseny efekt Oblicej se vrati na vychozi neon.
        let s = Settings::from_json(r#"{"tap_effect":"face"}"#).unwrap();
        assert_eq!(s.tap_effect, "neon");
    }

    #[test]
    fn ghost_tap_effect_is_valid() {
        let s = Settings::from_json(r#"{"tap_effect":"ghost"}"#).unwrap();
        assert_eq!(s.tap_effect, "ghost");
        let s = Settings::from_json(r#"{"tap_effect":"bogus"}"#).unwrap();
        assert_eq!(s.tap_effect, Settings::default().tap_effect);
    }

    #[test]
    fn special_outline_and_second_state_colors() {
        let d = Settings::default();
        assert!(d.special_outline);
        assert_eq!(
            (d.trail_color2.as_str(), d.glow_color2.as_str(), d.tap_text_color2.as_str(), d.outline_tap_color2.as_str()),
            ("", "", "", "")
        );
        let s = Settings::from_json(
            r##"{"special_outline":false,"trail_color2":"#abcdef","glow_color2":"rainbow","tap_text_color2":"bad","outline_tap_color2":"#00ff00"}"##,
        )
        .unwrap();
        assert!(!s.special_outline);
        assert_eq!(
            (s.trail_color2.as_str(), s.glow_color2.as_str(), s.tap_text_color2.as_str(), s.outline_tap_color2.as_str()),
            ("#ABCDEF", "", "", "#00FF00")
        );
        for k in ["special_outline", "trail_color2", "glow_color2", "tap_text_color2", "outline_tap_color2"] {
            assert!(STYLE_KEYS.contains(&k), "{k}");
        }
        assert_eq!(Settings::from_json(&s.to_json()).unwrap(), s);
    }
}
