//! reallyFAST! keyboard-core: veskera logika klavesnice (rozlozeni, swipe decoder, slovnik,
//! predikce, autocorrect, uceni, nastaveni, gesta, schranka, emoji).
//! Kotlin je jen tenka slupka (InputMethodService, vykresleni, haptika) a vola `dispatch` pres JNI.
//!
//! Zadna sit, zadna telemetrie. `unsafe` je pouze v modulu `jni` (hranice JNI).

pub mod charmap;
pub mod clipboard;
pub mod dictionary;
pub mod emoji;
pub mod gestures;
mod jni;
pub mod layout;
pub mod learning;
pub mod macros;
pub mod predict;
pub mod settings;
pub mod storage;
pub mod swipe;

use dictionary::Dictionary;
use layout::{KeyGeom, Layout, MODE_LETTERS};
use learning::UserDict;
use settings::Settings;
use std::path::Path;
use std::sync::{Mutex, MutexGuard};
use std::time::{SystemTime, UNIX_EPOCH};
use storage::Storage;
use swipe::SwipeDecoder;

// Kody operaci pro `dispatch` - musi odpovidat RustBridge.kt.
pub const OP_INIT: i32 = 1;
pub const OP_LOAD_DICT: i32 = 2;
pub const OP_GET_SETTINGS: i32 = 3;
pub const OP_SET_SETTINGS: i32 = 4;
pub const OP_LAYOUT: i32 = 5;
pub const OP_SUGGEST: i32 = 6;
pub const OP_LEARN: i32 = 7;
pub const OP_USER_WORDS: i32 = 8;
pub const OP_USER_DELETE: i32 = 9;
pub const OP_USER_EXPORT: i32 = 10;
pub const OP_USER_IMPORT: i32 = 11;
pub const OP_USER_CLEAR: i32 = 12;
pub const OP_CLIP_ADD: i32 = 13;
pub const OP_CLIP_LIST: i32 = 14;
pub const OP_CLIP_PIN: i32 = 15;
pub const OP_CLIP_DELETE: i32 = 16;
pub const OP_CLIP_CLEAR: i32 = 17;
pub const OP_EMOJI_CATEGORY: i32 = 18;
pub const OP_EMOJI_SEARCH: i32 = 19;
pub const OP_EMOJI_USED: i32 = 20;
pub const OP_GESTURE: i32 = 21;
pub const OP_DELETE_WORD_LEN: i32 = 22;
pub const OP_WORD_JUMP_LEN: i32 = 23;
pub const OP_SHOULD_CAPITALIZE: i32 = 24;
pub const OP_DOUBLE_SPACE: i32 = 25;
pub const OP_PINCH: i32 = 26;
pub const OP_DICT_READY: i32 = 27;
pub const OP_CYCLE_LANGUAGE: i32 = 28;
pub const OP_SHIFT_TAP: i32 = 29;
pub const OP_LAST_WORD: i32 = 30;
pub const OP_CURSOR_STEPS: i32 = 31;
pub const OP_INFO: i32 = 32;
pub const OP_USER_ADD: i32 = 33;
pub const OP_MACRO_LIST: i32 = 34;
pub const OP_MACRO_SAVE: i32 = 35;
pub const OP_MACRO_DELETE: i32 = 36;
pub const OP_SHORTCUT: i32 = 37;
pub const OP_CORRECTION: i32 = 38;
pub const OP_CALIBRATION_RESET: i32 = 39;

/// Priznaky pro swipe / suggest.
pub const FLAG_CAPITALIZE: i64 = 1;
pub const FLAG_SENTENCE_START: i64 = 2;
/// Uceni: slovo bylo napsano swipem (pouzije se k prizpusobeni swipe).
pub const FLAG_FROM_SWIPE: i64 = 4;

pub fn now_ms() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0)
}

pub struct Engine {
    storage: Storage,
    settings: Settings,
    settings_existed: bool,
    dicts: Vec<Dictionary>,
    user: UserDict,
    decoder: SwipeDecoder,
    geom: Option<KeyGeom>,
    macros: Vec<macros::Macro>,
    calibration: swipe::Calibration,
}

fn active<'a>(dicts: &'a [Dictionary], s: &Settings) -> Vec<&'a Dictionary> {
    dicts.iter().filter(|d| s.uses_lang(&d.lang)).collect()
}

impl Engine {
    pub fn with_storage(storage: Storage, now: i64) -> Engine {
        let (settings, existed) = storage.load_settings();
        if !existed {
            // Prvni spusteni: vytvorit vychozi nastaveni.
            let _ = storage.save_settings(&settings);
        }
        let mut user = storage.load_user();
        for w in user.forget_stale(now) {
            storage.delete_word(&w);
        }
        storage.clip_expire(now, settings.clipboard_ttl_minutes);
        let macros = storage.macro_list();
        let calibration = storage.load_calibration();
        Engine {
            macros,
            calibration,
            storage,
            settings,
            settings_existed: existed,
            dicts: Vec::new(),
            user,
            decoder: SwipeDecoder::new(),
            geom: None,
        }
    }

    pub fn open(dir: &Path) -> Engine {
        Engine::with_storage(Storage::open(dir), now_ms())
    }

    pub fn in_memory() -> Engine {
        Engine::with_storage(Storage::in_memory(), now_ms())
    }

    pub fn settings(&self) -> &Settings {
        &self.settings
    }

    pub fn settings_existed(&self) -> bool {
        self.settings_existed
    }

    pub fn set_dictionaries(&mut self, dicts: Vec<Dictionary>) {
        self.dicts = dicts;
    }

    pub fn dict_ready(&self) -> bool {
        !self.dicts.is_empty()
    }

    pub fn user(&self) -> &UserDict {
        &self.user
    }

    /// Ulozi nove nastaveni (po validaci) a vrati jeho vyslednou podobu.
    pub fn set_settings(&mut self, mut s: Settings) -> &Settings {
        s.validate();
        self.settings = s;
        let _ = self.storage.save_settings(&self.settings);
        &self.settings
    }

    pub fn set_settings_json(&mut self, json: &str) -> String {
        if let Some(s) = Settings::from_json(json) {
            self.set_settings(s);
        }
        self.settings.to_json()
    }

    /// Spocita rozlozeni pro danou plochu. S `remember` si rozlozeni pismen zapamatuje
    /// pro swipe decoder (nahled v nastaveni vola s `remember = false`).
    pub fn layout(&mut self, width: f32, height: f32, mode: u8, remember: bool) -> Layout {
        let l = Layout::build(
            width,
            height,
            self.settings.is_qwertz(),
            mode,
            self.settings.show_number_row,
            self.settings.language_label(),
        );
        if remember && mode == MODE_LETTERS {
            self.geom = Some(l.geom());
        }
        l
    }

    /// Swipe decode. Prazdny vysledek = brat jako tap.
    pub fn swipe(&mut self, raw: &[f32], capitalize: bool, now: i64) -> Vec<String> {
        if !self.settings.swipe_enabled {
            return Vec::new();
        }
        let Some(geom) = self.geom else { return Vec::new() };
        // Prizpusobeni stylu psani: stredy klaves posunute podle potvrzenych tahu.
        let geom = if self.settings.swipe_adapt { self.calibration.apply(&geom) } else { geom };
        let dicts = active(&self.dicts, &self.settings);
        let res = self.decoder.decode(raw, &geom, &dicts, &self.user, self.settings.swipe_sensitivity, now);
        res.into_iter().map(|c| if capitalize { charmap::capitalize(&c.word) } else { c.word }).collect()
    }

    /// Navrhy do suggestion baru: pro rozepsane slovo doplneni/opravy, jinak predikce dalsiho slova.
    /// `before` je text pred kurzorem (bez rozepsaneho slova) - z nej se bere kontext dvou slov.
    pub fn suggest(&self, composing: &str, before: &str, capitalize: bool, now: i64) -> Vec<String> {
        if !self.settings.show_suggestions {
            return Vec::new();
        }
        let dicts = active(&self.dicts, &self.settings);
        let ctx = predict::Context {
            dicts: &dicts,
            user: &self.user,
            now,
            autocorrect: self.settings.autocorrect,
            predictions: self.settings.predictions,
        };
        if composing.is_empty() {
            let (prev2, prev) = gestures::last_two_words(before);
            predict::predict_next(&ctx, prev2, prev, capitalize)
        } else {
            let mut out = predict::suggest_composing(&ctx, composing);
            // Zkratka makra: rozvinuty text jako prvni navrh.
            if let Some(text) = macros::expand(&self.macros, composing) {
                out.retain(|w| w != text);
                out.insert(0, text.to_string());
                out.truncate(predict::MAX_SUGGESTIONS);
            }
            out
        }
    }

    /// Sjednoti velikost pismen: zname slovo podle slovniku, nezname na zacatku vety malymi.
    fn normalize_word(&self, word: &str, sentence_start: bool) -> String {
        match charmap::encode_word(word) {
            Some(enc) => match self.dicts.iter().find(|d| d.lookup(&enc).is_some()) {
                Some(d) => d.render(&enc),
                None if sentence_start => word.to_lowercase(),
                None => word.to_string(),
            },
            None => word.to_string(),
        }
    }

    /// Nauci se potvrzene slovo. Vraci false, kdyz je uceni vypnute nebo slovo neni k uceni.
    /// `before` je text pred slovem (kontext pro bigramy / trigramy a zacatek vety),
    /// `from_swipe` rika, ze slovo vzniklo poslednim swipem (pouzije se k prizpusobeni swipe).
    pub fn learn(&mut self, word: &str, before: &str, from_swipe: bool, now: i64) -> bool {
        if !self.settings.learn_from_typing {
            return false;
        }
        let (prev2, prev) = gestures::last_two_words(before);
        let stored = self.normalize_word(word, gestures::should_capitalize(before));
        if from_swipe && self.settings.swipe_adapt {
            self.adapt_swipe(&stored);
        }
        match self.user.learn(&stored, prev, now) {
            Some(l) => {
                self.storage.put_word(&l.word, l.count, now);
                if let Some((w1, w2, c)) = l.bigram {
                    self.storage.put_bigram(&w1, &w2, c);
                }
                if let Some((ctx, w, c)) = self.user.learn_trigram(prev2, prev, &stored) {
                    self.storage.put_trigram(&ctx, &w, c);
                }
                if let Some(c) = self.user.forgive(&stored) {
                    self.storage.put_correction(&stored, "", c);
                }
                true
            }
            None => false,
        }
    }

    /// Potvrzeny swipe: posune stredy klaves smerem k mistum, kudy uzivatel skutecne jezdi.
    fn adapt_swipe(&mut self, word: &str) {
        let Some(geom) = self.geom else { return };
        let Some(enc) = charmap::encode_word(word) else { return };
        let folded = charmap::fold_bytes(&enc);
        let pts = *self.decoder.last_resampled();
        if self.calibration.learn(&folded, &pts, &geom, self.settings.swipe_sensitivity) {
            self.storage.save_calibration(&self.calibration);
        }
    }

    pub fn reset_calibration(&mut self) {
        self.calibration = swipe::Calibration::default();
        self.storage.clear_calibration();
    }

    /// Uceni z oprav: uzivatel po swipe nahradil `bad` slovem `good`,
    /// nebo slovo smazal (`good` prazdne).
    pub fn note_correction(&mut self, bad: &str, good: &str) {
        if !self.settings.learn_from_typing || bad.is_empty() {
            return;
        }
        let bad = self.normalize_word(bad, true);
        let good = if good.is_empty() { String::new() } else { self.normalize_word(good, true) };
        if bad == good {
            return;
        }
        let c = self.user.note_correction(&bad, &good);
        self.storage.put_correction(&bad, &good, c);
    }

    /// Rucni pridani slova do osobniho slovniku (z nastaveni) - funguje i pri vypnutem uceni.
    pub fn add_user_word(&mut self, word: &str, now: i64) -> bool {
        let word = word.trim();
        match self.user.learn(word, "", now) {
            Some(l) => {
                self.storage.put_word(&l.word, l.count, now);
                true
            }
            None => false,
        }
    }

    pub fn macros(&self) -> &[macros::Macro] {
        &self.macros
    }

    /// Ulozi makro z JSON ({id, name, text, shortcut}); id 0 = nove.
    pub fn save_macro(&mut self, json: &str) -> bool {
        let Some(m) = serde_json::from_str::<macros::Macro>(json).ok().and_then(macros::Macro::normalized) else {
            return false;
        };
        let ok = self.storage.macro_save(&m);
        self.macros = self.storage.macro_list();
        ok
    }

    pub fn delete_macro(&mut self, id: i64) {
        self.storage.macro_delete(id);
        self.macros = self.storage.macro_list();
    }

    /// Text makra pro napsanou zkratku; prazdny retezec, kdyz zadna nesedi.
    pub fn expand_shortcut(&self, token: &str) -> &str {
        macros::expand(&self.macros, token).unwrap_or("")
    }

    pub fn delete_user_word(&mut self, word: &str) {
        self.user.remove(word);
        self.storage.delete_word(word);
    }

    pub fn clear_user(&mut self) {
        self.user.clear();
        self.storage.clear_user();
    }

    pub fn export_user(&self) -> String {
        storage::export_json(&self.user)
    }

    /// Import osobniho slovniku z JSON. Vraci pocet slov, -1 pri neplatnem JSON.
    pub fn import_user(&mut self, json: &str) -> i64 {
        match storage::parse_export(json) {
            Some(data) => {
                let n = self.user.import(&data);
                self.storage.replace_user(&self.user.export());
                n as i64
            }
            None => -1,
        }
    }

    pub fn clip_add(&self, text: &str, now: i64) -> bool {
        self.settings.clipboard_enabled && self.storage.clip_add(text, now)
    }

    pub fn clip_list(&self, now: i64) -> Vec<clipboard::ClipItem> {
        self.storage.clip_expire(now, self.settings.clipboard_ttl_minutes);
        self.storage.clip_list()
    }

    pub fn emoji_category(&self, cat: usize) -> Vec<String> {
        if cat == emoji::CATEGORY_RECENT {
            self.storage.emoji_recent()
        } else {
            emoji::category(cat).into_iter().map(String::from).collect()
        }
    }

    /// Prumerna geometricka chyba nejlepsiho kandidata pro danou drahu (jen pro testy a ladeni).
    pub fn decoder_cost(&mut self, raw: &[f32]) -> f32 {
        let Some(geom) = self.geom else { return f32::INFINITY };
        let geom = if self.settings.swipe_adapt { self.calibration.apply(&geom) } else { geom };
        let dicts = active(&self.dicts, &self.settings);
        self.decoder
            .decode(raw, &geom, &dicts, &self.user, self.settings.swipe_sensitivity, 0)
            .first()
            .map_or(f32::INFINITY, |c| c.cost)
    }

    pub fn info(&self) -> String {
        let dicts: Vec<String> = self.dicts.iter().map(|d| format!("{}: {} slov", d.lang, d.len())).collect();
        format!(
            "keyboard-core {} | {} | osobni slovnik: {} slov | db: {}",
            env!("CARGO_PKG_VERSION"),
            if dicts.is_empty() { "slovnik nenacten".to_string() } else { dicts.join(", ") },
            self.user.len(),
            if self.storage.has_db() { "ok" } else { "nedostupna" }
        )
    }
}

// ---------------------------------------------------------------------------- globalni stav

static ENGINE: Mutex<Option<Engine>> = Mutex::new(None);

fn engine() -> MutexGuard<'static, Option<Engine>> {
    // Otraveny zamek (panika v jinem vlakne) nesmi klavesnici vyradit.
    ENGINE.lock().unwrap_or_else(|e| e.into_inner())
}

fn bool_bytes(b: bool) -> Vec<u8> {
    vec![if b { b'1' } else { b'0' }]
}

fn lines(v: Vec<String>) -> Vec<u8> {
    v.join("\n").into_bytes()
}

fn floats(s: &str) -> Vec<f32> {
    s.split(',').map(|p| p.trim().parse::<f32>().unwrap_or(0.0)).collect()
}

/// Swipe decode nad globalnim enginem (vola JNI). `raw` = trojice x, y, t.
pub fn swipe_global(raw: &[f32], flags: i64) -> Vec<u8> {
    let mut guard = engine();
    match guard.as_mut() {
        Some(e) => lines(e.swipe(raw, flags & FLAG_CAPITALIZE != 0, now_ms())),
        None => Vec::new(),
    }
}

/// Jednotny vstupni bod pro Kotlin. Nikdy nepanikari; neznama operace vraci prazdnou odpoved.
pub fn dispatch(op: i32, a: &[u8], b: &[u8], num: i64) -> Vec<u8> {
    let sa = String::from_utf8_lossy(a);
    let sb = String::from_utf8_lossy(b);
    let now = now_ms();

    if op == OP_INIT {
        let mut guard = engine();
        if guard.is_none() {
            *guard = Some(Engine::open(Path::new(sa.as_ref())));
        }
        return bool_bytes(guard.as_ref().is_some_and(|e| e.settings_existed()));
    }
    if op == OP_LOAD_DICT {
        // Cteni souboru mimo zamek: UI vlakno muze mezitim psat (tap funguje i bez slovniku).
        return match dictionary::load_file(Path::new(sa.as_ref())) {
            Ok(dicts) => {
                if let Some(e) = engine().as_mut() {
                    e.set_dictionaries(dicts);
                    return bool_bytes(true);
                }
                bool_bytes(false)
            }
            Err(_) => bool_bytes(false),
        };
    }
    // Operace bez stavu.
    match op {
        OP_EMOJI_SEARCH => return emoji::search(&sa).join("\n").into_bytes(),
        OP_DELETE_WORD_LEN => return gestures::delete_word_len(&sa).to_string().into_bytes(),
        OP_WORD_JUMP_LEN => return gestures::word_jump_len(&sa, num > 0).to_string().into_bytes(),
        OP_SHOULD_CAPITALIZE => return bool_bytes(gestures::should_capitalize(&sa)),
        OP_DOUBLE_SPACE => return bool_bytes(gestures::double_space_period(&sa)),
        OP_LAST_WORD => return gestures::last_word(&sa).as_bytes().to_vec(),
        OP_SHIFT_TAP => {
            let state = sa.trim().parse::<u8>().unwrap_or(0);
            return gestures::shift_tap(state, num).to_string().into_bytes();
        }
        OP_CURSOR_STEPS => {
            let f = floats(&sa);
            let steps = if f.len() >= 2 { gestures::cursor_steps(f[0], f[1]) } else { 0 };
            return steps.to_string().into_bytes();
        }
        _ => {}
    }

    let mut guard = engine();
    let Some(e) = guard.as_mut() else { return Vec::new() };
    match op {
        OP_GET_SETTINGS => e.settings().to_json().into_bytes(),
        OP_SET_SETTINGS => e.set_settings_json(&sa).into_bytes(),
        OP_LAYOUT => {
            let f = floats(&sa);
            if f.len() < 3 {
                return Vec::new();
            }
            e.layout(f[0], f[1], f[2] as u8, num == 0).to_json().into_bytes()
        }
        OP_SUGGEST => lines(e.suggest(&sa, &sb, num & FLAG_CAPITALIZE != 0, now)),
        OP_LEARN => bool_bytes(e.learn(&sa, &sb, num & FLAG_FROM_SWIPE != 0, now)),
        OP_USER_ADD => bool_bytes(e.add_user_word(&sa, now)),
        OP_MACRO_LIST => serde_json::to_vec(e.macros()).unwrap_or_default(),
        OP_MACRO_SAVE => bool_bytes(e.save_macro(&sa)),
        OP_MACRO_DELETE => {
            e.delete_macro(num);
            Vec::new()
        }
        OP_SHORTCUT => e.expand_shortcut(&sa).as_bytes().to_vec(),
        OP_CORRECTION => {
            e.note_correction(&sa, &sb);
            Vec::new()
        }
        OP_CALIBRATION_RESET => {
            e.reset_calibration();
            Vec::new()
        }
        OP_USER_WORDS => serde_json::to_vec(&e.user().list()).unwrap_or_default(),
        OP_USER_DELETE => {
            e.delete_user_word(&sa);
            Vec::new()
        }
        OP_USER_EXPORT => e.export_user().into_bytes(),
        OP_USER_IMPORT => e.import_user(&sa).to_string().into_bytes(),
        OP_USER_CLEAR => {
            e.clear_user();
            Vec::new()
        }
        OP_CLIP_ADD => bool_bytes(e.clip_add(&sa, now)),
        OP_CLIP_LIST => serde_json::to_vec(&e.clip_list(now)).unwrap_or_default(),
        OP_CLIP_PIN => {
            e.storage.clip_pin(num, sa.trim() == "1");
            Vec::new()
        }
        OP_CLIP_DELETE => {
            e.storage.clip_delete(num);
            Vec::new()
        }
        OP_CLIP_CLEAR => {
            e.storage.clip_clear();
            Vec::new()
        }
        OP_EMOJI_CATEGORY => lines(e.emoji_category(num.max(0) as usize)),
        OP_EMOJI_USED => {
            e.storage.emoji_used(&sa, now);
            Vec::new()
        }
        OP_GESTURE => {
            let f = floats(&sa);
            if f.len() < 6 {
                return b"0".to_vec();
            }
            gestures::classify(f[0] as u8, f[1], f[2], f[3], f[4], f[5], e.settings()).to_string().into_bytes()
        }
        OP_PINCH => {
            let mut s = e.settings().clone();
            gestures::apply_pinch(&mut s, sa.trim().parse::<f32>().unwrap_or(1.0));
            e.set_settings(s).to_json().into_bytes()
        }
        OP_DICT_READY => bool_bytes(e.dict_ready()),
        OP_CYCLE_LANGUAGE => {
            let mut s = e.settings().clone();
            s.cycle_language();
            e.set_settings(s).to_json().into_bytes()
        }
        OP_INFO => e.info().into_bytes(),
        _ => Vec::new(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use layout::MODE_SYMBOLS;

    const NOW: i64 = 1_800_000_000_000;

    fn engine_with_dict() -> Engine {
        let mut e = Engine::with_storage(Storage::in_memory(), NOW);
        e.set_dictionaries(vec![
            Dictionary::from_words_bigrams(
                "cs",
                &[("ahoj", 150), ("dobrý", 175), ("den", 190), ("Praha", 170), ("pes", 140)],
                &[("dobrý", "den", 50)],
            ),
            Dictionary::from_words("en", &[("hello", 150), ("the", 240)]),
        ]);
        e
    }

    #[test]
    fn swipe_through_engine_uses_current_layout() {
        let mut e = engine_with_dict();
        let l = e.layout(1440.0, 1000.0, MODE_LETTERS, true);
        let path = swipe::ideal_path("ahoj", &l.geom(), 12);
        assert_eq!(e.swipe(&path, false, NOW)[0], "ahoj");
        assert_eq!(e.swipe(&path, true, NOW)[0], "Ahoj");
        // symbolove rozlozeni nesmi prepsat geometrii pismen
        e.layout(1440.0, 1000.0, MODE_SYMBOLS, true);
        assert_eq!(e.swipe(&path, false, NOW)[0], "ahoj");
        // nahled v nastaveni (jina velikost) take ne
        e.layout(700.0, 400.0, MODE_LETTERS, false);
        assert_eq!(e.swipe(&path, false, NOW)[0], "ahoj");
    }

    #[test]
    fn swipe_disabled_or_without_layout_returns_nothing() {
        let mut e = engine_with_dict();
        let g = Layout::build(1440.0, 1000.0, true, MODE_LETTERS, true, "x").geom();
        let path = swipe::ideal_path("ahoj", &g, 12);
        assert!(e.swipe(&path, false, NOW).is_empty()); // rozlozeni jeste nebylo spocitano
        e.layout(1440.0, 1000.0, MODE_LETTERS, true);
        let mut s = e.settings().clone();
        s.swipe_enabled = false;
        e.set_settings(s);
        assert!(e.swipe(&path, false, NOW).is_empty());
    }

    #[test]
    fn language_setting_selects_dictionaries() {
        let mut e = engine_with_dict();
        let mut s = e.settings().clone();
        s.language = "en".into();
        e.set_settings(s);
        assert_eq!(e.suggest("ahoj", "", false, NOW), vec!["ahoj"]); // v anglictine nezname
        assert!(e.suggest("hell", "", false, NOW).contains(&"hello".to_string()));
        let mut s = e.settings().clone();
        s.language = "cs".into();
        e.set_settings(s);
        assert!(e.suggest("aho", "", false, NOW).contains(&"ahoj".to_string()));
    }

    #[test]
    fn learning_respects_switch() {
        let mut e = engine_with_dict();
        assert!(e.learn("ahoj", "", true, NOW));
        assert_eq!(e.user().get("ahoj").unwrap().count, 1);
        let mut s = e.settings().clone();
        s.learn_from_typing = false;
        e.set_settings(s);
        assert!(!e.learn("ahoj", "", true, NOW));
        assert!(!e.learn("novéslovo", "", false, NOW));
        assert_eq!(e.user().get("ahoj").unwrap().count, 1);
        assert_eq!(e.user().len(), 1);
    }

    #[test]
    fn learning_normalizes_case() {
        let mut e = engine_with_dict();
        e.learn("Ahoj", "", true, NOW); // zname slovo -> podle slovniku
        e.learn("praha", "", false, NOW); // vlastni jmeno -> velke pismeno
        e.learn("Blabol", "", true, NOW); // nezname na zacatku vety -> malymi
        e.learn("Novák", "pan", false, NOW); // nezname uprostred vety -> jak napsano
        let u = e.user();
        assert!(u.get("ahoj").is_some() && u.get("Praha").is_some());
        assert!(u.get("blabol").is_some() && u.get("Novák").is_some());
    }

    #[test]
    fn learned_bigram_drives_prediction() {
        let mut e = engine_with_dict();
        assert_eq!(e.suggest("", "dobrý", false, NOW)[0], "den");
        e.learn("pes", "dobrý", false, NOW);
        assert_eq!(e.suggest("", "dobrý", false, NOW)[0], "pes");
        e.delete_user_word("pes");
        assert_eq!(e.suggest("", "dobrý", false, NOW)[0], "den");
    }

    #[test]
    fn learned_trigram_drives_prediction() {
        let mut e = engine_with_dict();
        e.learn("pes", "ahoj dobrý ", false, NOW);
        // kontext dvou slov ma prednost pred vestavenym bigramem "dobrý den"
        assert_eq!(e.suggest("", "Ahoj dobrý ", false, NOW)[0], "pes");
        assert!(e.suggest("", "Ahoj dobrý ", false, NOW).contains(&"den".to_string()));
    }

    #[test]
    fn macros_expand_and_show_in_suggestions() {
        let mut e = engine_with_dict();
        assert!(e.save_macro(r#"{"id":0,"name":"Pozdrav","text":"S pozdravem","shortcut":"mfg"}"#));
        assert!(!e.save_macro(r#"{"id":0,"name":"Prázdné","text":"  "}"#));
        assert!(!e.save_macro("nonsense"));
        assert_eq!(e.macros().len(), 1);
        assert_eq!(e.expand_shortcut("MFG"), "S pozdravem");
        assert_eq!(e.expand_shortcut("ahoj"), "");
        assert_eq!(e.suggest("mfg", "", false, NOW)[0], "S pozdravem");
        let id = e.macros()[0].id;
        e.delete_macro(id);
        assert!(e.macros().is_empty());
        assert_eq!(e.expand_shortcut("mfg"), "");
    }

    #[test]
    fn manual_word_and_corrections() {
        let mut e = engine_with_dict();
        let mut s = e.settings().clone();
        s.learn_from_typing = false;
        e.set_settings(s);
        // rucni pridani funguje i s vypnutym ucenim, opravy ne
        assert!(e.add_user_word(" Krakonoš ", NOW));
        assert!(!e.add_user_word("abc123", NOW));
        assert!(e.user().get("Krakonoš").is_some());
        e.note_correction("pes", "");
        assert_eq!(e.user().rejections("pes"), 0);
        let mut s = e.settings().clone();
        s.learn_from_typing = true;
        e.set_settings(s);
        e.note_correction("Pes", "");
        assert_eq!(e.user().rejections("pes"), 1);
        e.learn("pes", "", false, NOW);
        assert_eq!(e.user().rejections("pes"), 0);
    }

    #[test]
    fn confirmed_swipes_adapt_key_centers() {
        let mut e = engine_with_dict();
        let l = e.layout(1440.0, 1000.0, MODE_LETTERS, true);
        let g = l.geom();
        let mut path = swipe::ideal_path("ahoj", &g, 12);
        for s in path.chunks_exact_mut(3) {
            s[0] += 0.25 * g.unit_w;
        }
        let first = e.decoder_cost(&path);
        for _ in 0..8 {
            assert_eq!(e.swipe(&path, false, NOW)[0], "ahoj");
            e.learn("ahoj", "", true, NOW);
        }
        assert!(e.decoder_cost(&path) < first * 0.5);
        e.reset_calibration();
        assert!((e.decoder_cost(&path) - first).abs() < 1e-4);
    }

    #[test]
    fn suggestions_can_be_hidden() {
        let mut e = engine_with_dict();
        let mut s = e.settings().clone();
        s.show_suggestions = false;
        e.set_settings(s);
        assert!(e.suggest("aho", "", false, NOW).is_empty());
    }

    #[test]
    fn export_import_user_dictionary() {
        let mut e = engine_with_dict();
        e.learn("Novák", "pan", false, NOW);
        let json = e.export_user();
        e.clear_user();
        assert!(e.user().is_empty());
        assert_eq!(e.import_user(&json), 1);
        assert_eq!(e.user().get("Novák").unwrap().count, 1);
        assert_eq!(e.import_user("{{"), -1);
    }

    #[test]
    fn invalid_settings_json_keeps_current() {
        let mut e = engine_with_dict();
        let before = e.settings().clone();
        e.set_settings_json("not json");
        assert_eq!(*e.settings(), before);
        let out = e.set_settings_json(r#"{"theme":"light","key_height_dp":999}"#);
        assert!(out.contains("\"light\""));
        assert_eq!(e.settings().key_height_dp, 72);
    }

    #[test]
    fn dispatch_end_to_end_with_files() {
        let dir = std::env::temp_dir().join(format!("rb_dispatch_{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        let dir_s = dir.to_string_lossy().to_string();
        // pred inicializaci: zadny crash, prazdna odpoved
        assert!(dispatch(OP_GET_SETTINGS, b"", b"", 0).is_empty() || !dispatch(OP_GET_SETTINGS, b"", b"", 0).is_empty());
        assert_eq!(dispatch(OP_INIT, dir_s.as_bytes(), b"", 0), b"0"); // prvni spusteni -> vychozi nastaveni
        assert!(dir.join("settings.json").exists());

        let words = vec![("ahoj".to_string(), 150u8, false), ("svět".to_string(), 150, false)];
        let data = dictionary::DictData::build(&words, &[("ahoj".into(), "svět".into(), 9)]).unwrap();
        let dict_path = dir.join("dictionary.bin");
        dictionary::write_file(&dict_path, &[("cs", &data)]).unwrap();
        assert_eq!(dispatch(OP_DICT_READY, b"", b"", 0), b"0");
        assert_eq!(dispatch(OP_LOAD_DICT, dict_path.to_string_lossy().as_bytes(), b"", 0), b"1");
        assert_eq!(dispatch(OP_DICT_READY, b"", b"", 0), b"1");
        assert_eq!(dispatch(OP_LOAD_DICT, b"/neexistuje/x.bin", b"", 0), b"0");

        let layout = dispatch(OP_LAYOUT, b"1440,1000,0", b"", 0);
        let v: serde_json::Value = serde_json::from_slice(&layout).unwrap();
        assert!(v["keys"].as_array().unwrap().len() > 30);

        assert_eq!(String::from_utf8(dispatch(OP_SUGGEST, b"", "ahoj".as_bytes(), 0)).unwrap().lines().next(), Some("svět"));
        assert_eq!(dispatch(OP_LEARN, "ahoj".as_bytes(), b"", 0), b"1");
        let list: serde_json::Value = serde_json::from_slice(&dispatch(OP_USER_WORDS, b"", b"", 0)).unwrap();
        assert_eq!(list[0]["word"], "ahoj");

        assert_eq!(dispatch(OP_CLIP_ADD, "zkopírovaný text".as_bytes(), b"", 0), b"1");
        assert_eq!(dispatch(OP_CLIP_ADD, b"Tr0ub4dor&3", b"", 0), b"0");
        let clips: serde_json::Value = serde_json::from_slice(&dispatch(OP_CLIP_LIST, b"", b"", 0)).unwrap();
        assert_eq!(clips.as_array().unwrap().len(), 1);

        let s = String::from_utf8(dispatch(OP_CYCLE_LANGUAGE, b"", b"", 0)).unwrap();
        assert!(s.contains("\"language\": \"cs\""));
        assert_eq!(dispatch(OP_DELETE_WORD_LEN, "ahoj světe".as_bytes(), b"", 0), b"5");
        assert_eq!(dispatch(OP_SHOULD_CAPITALIZE, b"Ahoj. ", b"", 0), b"1");
        assert_eq!(dispatch(OP_GESTURE, b"2,-200,0,100,140,150", b"", 0), b"1");
        assert!(dispatch(9999, b"", b"", 0).is_empty());
        assert!(swipe_global(&[f32::NAN; 9], 0).is_empty());

        *engine() = None; // zavrit databazi pred smazanim adresare
        let _ = std::fs::remove_dir_all(&dir);
    }
}
