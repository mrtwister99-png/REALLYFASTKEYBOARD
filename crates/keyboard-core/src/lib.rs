//! fAZT! keyboard-core: veskera logika klavesnice (rozlozeni, swipe decoder, slovnik,
//! predikce, autocorrect, uceni, nastaveni, gesta, schranka, emoji).
//! Kotlin je jen tenka slupka (InputMethodService, vykresleni, haptika) a vola `dispatch` pres JNI.
//!
//! Zadna sit, zadna telemetrie. `unsafe` je pouze v modulu `jni` (hranice JNI).

pub mod backup;
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
pub mod voice;

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
/// Jen v Rustu (Kotlin stav slovniku nedotazuje, spoleha na listenery po nacteni); drzi se pro nastroje a testy.
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
pub const OP_SWIPE_LOG_EXPORT: i32 = 40;
pub const OP_SWIPE_LOG_CLEAR: i32 = 41;
pub const OP_SWIPE_LOG_COUNT: i32 = 42;
pub const OP_BACKUP_EXPORT: i32 = 43;
pub const OP_BACKUP_IMPORT: i32 = 44;
pub const OP_VOICE_LIST: i32 = 45;
pub const OP_VOICE_SAVE: i32 = 46;
pub const OP_VOICE_DELETE: i32 = 47;
pub const OP_VOICE_SPLIT: i32 = 48;
pub const OP_STYLE_SAVE: i32 = 49;
pub const OP_STYLE_LOAD: i32 = 50;
pub const OP_AUTO_ACCENT: i32 = 51;
pub const OP_PENDING_STYLES: i32 = 52;
pub const OP_PLACE_STYLE: i32 = 53;
pub const OP_DISCARD_STYLES: i32 = 54;

/// Priznaky pro swipe / suggest.
pub const FLAG_CAPITALIZE: i64 = 1;
/// Navrhy: misto 3 az 5 slov (podrzeni navrhu v liste ukaze dalsi dva).
pub const FLAG_MORE: i64 = 8;
/// Zkusebni tah z nahledu v nastaveni: nesmi skoncit v zaznamu tahu.
pub const FLAG_PREVIEW: i64 = 2;
/// Uceni: slovo bylo napsano swipem (pouzije se k prizpusobeni swipe).
pub const FLAG_FROM_SWIPE: i64 = 4;

pub fn now_ms() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0)
}

/// Posledni dekodovany swipe cekajici na vysledek (potvrzeni / vyber alternativy / smazani).
struct PendingSwipe {
    raw: Vec<f32>,
    geom: KeyGeom,
    first: String,
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
    voice: Vec<voice::VoiceCommand>,
    calibration: swipe::Calibration,
    swipe_pending: Option<PendingSwipe>,
    /// Styly ze zalohy, pro ktere nebylo misto (vsech 5 slotu obsazeno): ceka se na volbu uzivatele.
    pending_styles: Vec<serde_json::Value>,
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
        let voice = storage.voice_list();
        let calibration = storage.load_calibration();
        Engine {
            macros,
            voice,
            calibration,
            storage,
            settings,
            settings_existed: existed,
            dicts: Vec::new(),
            user,
            decoder: SwipeDecoder::new(),
            geom: None,
            swipe_pending: None,
            pending_styles: Vec::new(),
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

    /// Ulozi soucasny vzhled do slotu stylu (0..STYLE_SLOTS); jiny index nic nemeni.
    pub fn save_style(&mut self, slot: i64) {
        let Some(i) = style_index(slot) else { return };
        let mut s = self.settings.clone();
        let snap = serde_json::Value::Object(s.style_snapshot());
        if let Some(t) = s.style_slots.as_array_mut().and_then(|a| a.get_mut(i)) {
            *t = snap;
            self.set_settings(s);
        }
    }

    /// Styly ze zalohy, pro ktere nebylo misto (cekaji na volbu, ktery slot prepsat).
    pub fn pending_styles(&self) -> &[serde_json::Value] {
        &self.pending_styles
    }

    /// Prvni cekajici styl ulozi do slotu `slot` (puvodni obsah se prepise) a odebere ho z fronty.
    pub fn place_pending_style(&mut self, slot: i64) {
        let Some(i) = style_index(slot) else { return };
        if self.pending_styles.is_empty() {
            return;
        }
        let style = self.pending_styles.remove(0);
        let mut s = self.settings.clone();
        if let Some(t) = s.style_slots.as_array_mut().and_then(|a| a.get_mut(i)) {
            *t = style;
            self.set_settings(s);
        }
    }

    /// Zahodi vsechny cekajici styly.
    pub fn discard_pending_styles(&mut self) {
        self.pending_styles.clear();
    }

    /// Nacte vzhled ze slotu stylu; ostatni nastaveni zustane. Jiny index nic nemeni.
    pub fn load_style(&mut self, slot: i64) {
        let Some(i) = style_index(slot) else { return };
        let Some(saved) = self.settings.style_slots.get(i).and_then(|v| v.as_object()).cloned() else { return };
        let s = self.settings.with_style(&saved);
        self.set_settings(s);
    }

    /// Spocita rozlozeni pro danou plochu. S `remember` si rozlozeni pismen zapamatuje
    /// pro swipe decoder (nahled v nastaveni vola s `remember = false`).
    pub fn layout(&mut self, width: f32, height: f32, mode: u8, remember: bool) -> Layout {
        self.layout_with_offsets(width, height, mode, remember, None)
    }

    /// Jako `layout`, ale s vlastnimi posuny radku (nahled upravy radku v nastaveni) misto ulozenych.
    pub fn layout_with_offsets(&mut self, width: f32, height: f32, mode: u8, remember: bool, offsets: Option<&[f32]>) -> Layout {
        let mut l = Layout::build_with(
            width,
            height,
            self.settings.is_qwertz(),
            mode,
            self.settings.show_number_row,
            self.settings.language_label(),
            layout::BottomKeys {
                macros: layout::KeyPlace::parse(&self.settings.macro_key),
                swap_mode_enter: self.settings.swap_mode_enter,
                swap_shift_delete: self.settings.swap_shift_delete,
            },
        );
        // Premapovani pred zapamatovanim geometrie, aby swipe sel podle noveho rozlozeni.
        l.apply_key_map(&self.settings.key_map);
        l.apply_key_alts(&self.settings.key_alts);
        l.apply_row_offsets(offsets.unwrap_or(&self.settings.row_offsets));
        if remember && mode == MODE_LETTERS {
            self.geom = Some(l.geom());
        }
        l
    }

    /// Swipe decode. Prazdny vysledek = brat jako tap.
    pub fn swipe(&mut self, raw: &[f32], capitalize: bool, now: i64) -> Vec<String> {
        self.swipe_pending = None;
        if !self.settings.swipe_enabled {
            return Vec::new();
        }
        let Some(raw_geom) = self.geom else { return Vec::new() };
        // Prizpusobeni stylu psani: stredy klaves posunute podle potvrzenych tahu.
        let geom = if self.settings.swipe_adapt { self.calibration.apply(&raw_geom) } else { raw_geom };
        let dicts = active(&self.dicts, &self.settings);
        let res = self.decoder.decode(raw, &geom, &dicts, &self.user, self.settings.swipe_sensitivity, now);
        if self.settings.swipe_log && raw.iter().all(|v| v.is_finite()) {
            if let Some(first) = res.first() {
                // Geometrie se uklada pred prizpusobenim, aby sel tah presne prehrat.
                self.swipe_pending = Some(PendingSwipe { raw: raw.to_vec(), geom: raw_geom, first: first.word.clone() });
            }
        }
        res.into_iter().map(|c| if capitalize { charmap::capitalize(&c.word) } else { c.word }).collect()
    }

    /// Navrhy do suggestion baru: pro rozepsane slovo doplneni/opravy, jinak predikce dalsiho slova.
    /// `before` je text pred kurzorem (bez rozepsaneho slova) - z nej se bere kontext dvou slov.
    pub fn suggest(&self, composing: &str, before: &str, capitalize: bool, now: i64) -> Vec<String> {
        self.suggest_n(composing, before, capitalize, now, predict::MAX_SUGGESTIONS)
    }

    /// Jako `suggest`, ale nejvyse `limit` navrhu.
    pub fn suggest_n(&self, composing: &str, before: &str, capitalize: bool, now: i64, limit: usize) -> Vec<String> {
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
            predict::predict_next_n(&ctx, prev2, prev, capitalize, limit)
        } else {
            let mut out = predict::suggest_composing_n(&ctx, composing, limit);
            // Zkratka makra: rozvinuty text jako prvni navrh.
            if let Some(text) = macros::expand(&self.macros, composing) {
                out.retain(|w| w != text);
                out.insert(0, text.to_string());
                out.truncate(limit);
            }
            out
        }
    }

    /// Automaticke doplneni diakritiky pro slovo napsane pred mezerou; prazdny retezec = nechat jak je.
    pub fn auto_accent(&self, typed: &str, now: i64) -> String {
        if !self.settings.auto_diacritics {
            return String::new();
        }
        let dicts = active(&self.dicts, &self.settings);
        let ctx = predict::Context {
            dicts: &dicts,
            user: &self.user,
            now,
            autocorrect: self.settings.autocorrect,
            predictions: self.settings.predictions,
        };
        predict::auto_accent(&ctx, typed).unwrap_or_default()
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
        if from_swipe {
            self.finish_swipe_log(Some(word));
        }
        if !self.settings.learn_from_typing {
            return false;
        }
        let (prev2, prev) = gestures::last_two_words(before);
        let stored = self.normalize_word(word, gestures::should_capitalize(before));
        if from_swipe && self.settings.swipe_adapt {
            self.adapt_swipe(&stored);
        }
        let Some(l) = self.user.learn(&stored, prev, now) else { return false };
        // Slovo, bigram, trigram a oprava se zapisou v jedne transakci.
        let user = &mut self.user;
        let storage = &self.storage;
        storage.batch(|| {
            storage.put_word(&l.word, l.count, now);
            if let Some((w1, w2, c)) = l.bigram {
                storage.put_bigram(&w1, &w2, c);
            }
            if let Some((ctx, w, c)) = user.learn_trigram(prev2, prev, &stored) {
                storage.put_trigram(&ctx, &w, c);
            }
            if let Some(c) = user.forgive(&stored) {
                storage.put_correction(&stored, "", c);
            }
        });
        true
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
        if good.is_empty() && !bad.is_empty() {
            self.finish_swipe_log(None); // smazano hned po swipe
        }
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

    /// Zapomene rozdelany tah (zkusebni tah z nahledu se nema zapsat do zaznamu).
    pub fn discard_swipe_pending(&mut self) {
        self.swipe_pending = None;
    }

    /// Zapise zapamatovany tah do zaznamu, kdyz je zaznam zapnuty, a tah zapomene.
    /// `chosen` = potvrzene slovo (outcome 0 / 1), `None` = slovo smazano (outcome 2).
    fn finish_swipe_log(&mut self, chosen: Option<&str>) {
        let Some(p) = self.swipe_pending.take() else { return };
        if !self.settings.swipe_log {
            return;
        }
        let (word, outcome) = match chosen {
            Some(w) if w.is_empty() => return,
            Some(w) => (w, if w.to_lowercase() == p.first.to_lowercase() { 0 } else { 1 }),
            None => ("", 2),
        };
        let trace = storage::SwipeTrace {
            ts: now_ms(),
            word: word.to_string(),
            first: p.first.clone(),
            outcome,
            geom: p.geom,
            points: p.raw.clone(),
        };
        self.storage.swipe_log_add_trace(&trace);
    }

    pub fn swipe_log_count(&self) -> i64 {
        self.storage.swipe_log_count()
    }

    pub fn clear_swipe_log(&mut self) {
        self.storage.swipe_log_clear();
    }

    /// Zaznam tahu jako JSON (vstup nastroje `tune`).
    pub fn export_swipe_log(&self) -> String {
        let file = storage::SwipeLogFile {
            app: backup::APP_NAME.to_string(),
            version: backup::BACKUP_VERSION,
            swipes: self.storage.swipe_log_list(),
        };
        serde_json::to_string(&file).unwrap_or_else(|_| "{}".to_string())
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
        self.save_macro_id(json) > 0
    }

    /// Ulozi makro (jako `save_macro`) a vrati jeho id; 0 = nic se neulozilo.
    pub fn save_macro_id(&mut self, json: &str) -> i64 {
        let Some(m) = serde_json::from_str::<macros::Macro>(json).ok().and_then(macros::Macro::normalized) else {
            return 0;
        };
        let id = self.storage.macro_save_id(&m);
        self.macros = self.storage.macro_list();
        id
    }

    pub fn delete_macro(&mut self, id: i64) {
        self.storage.macro_delete(id);
        self.macros = self.storage.macro_list();
    }

    /// Text makra pro napsanou zkratku; prazdny retezec, kdyz zadna nesedi.
    pub fn expand_shortcut(&self, token: &str) -> &str {
        macros::expand(&self.macros, token).unwrap_or("")
    }

    pub fn voice_commands(&self) -> &[voice::VoiceCommand] {
        &self.voice
    }

    /// Ulozi hlasovy povel z JSON ({id, phrase, action, text}); id 0 = novy.
    pub fn save_voice(&mut self, json: &str) -> bool {
        let Some(c) = serde_json::from_str::<voice::VoiceCommand>(json).ok().and_then(voice::VoiceCommand::normalized) else {
            return false;
        };
        let ok = self.storage.voice_save(&c);
        self.voice = self.storage.voice_list();
        ok
    }

    pub fn delete_voice(&mut self, id: i64) {
        self.storage.voice_delete(id);
        self.voice = self.storage.voice_list();
    }

    /// Rozdeli nadiktovany text na obycejny text a povely (JSON pole segmentu).
    pub fn voice_split(&self, spoken: &str) -> String {
        serde_json::to_string(&voice::split(&self.voice, spoken)).unwrap_or_else(|_| "[]".to_string())
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

    /// Uplna zaloha (nastaveni, slovnik, opravy, makra, hlasove povely, prizpusobeni swipe, zaznam tahu) jako JSON.
    pub fn export_backup(&self, now: i64) -> String {
        let calibration = (0..26)
            .filter(|&k| self.calibration.n[k] > 0)
            .map(|k| backup::BackupKey {
                key: k as u32,
                sx: self.calibration.sum[k].0,
                sy: self.calibration.sum[k].1,
                n: self.calibration.n[k],
            })
            .collect();
        let data = backup::Backup {
            app: backup::APP_NAME.to_string(),
            version: backup::BACKUP_VERSION,
            created: now,
            settings: serde_json::to_value(&self.settings).ok(),
            user: self.user.export(),
            corrections: self
                .user
                .corrections_list()
                .into_iter()
                .map(|(bad, good, count)| backup::BackupCorrection { bad, good, count })
                .collect(),
            macros: self.macros.clone(),
            voice: self.voice.clone(),
            calibration,
            swipe_log: self.storage.swipe_log_list(),
            pinned_clips: self.storage.clip_pinned_texts(),
        };
        serde_json::to_string_pretty(&data).unwrap_or_else(|_| "{}".to_string())
    }

    /// Obnova ze zalohy. Nastaveni se prepise (stejna validace jako `set_settings_json`; sloty stylu jen kdyz jsou v zaloze),
    /// slovnik, opravy, makra a zaznam tahu se slouci, prizpusobeni swipe se nahradi (kdyz je v souboru).
    /// Vraci pocet prijatych slov slovniku, -1 pri neplatnem souboru.
    pub fn import_backup(&mut self, json: &str) -> i64 {
        let Ok(data) = serde_json::from_str::<backup::Backup>(json) else { return -1 };
        if data.app != backup::APP_NAME {
            return -1;
        }
        if let Some(v) = data.settings.as_ref().filter(|v| v.is_object()) {
            if let Some(mut s) = Settings::from_json(&v.to_string()) {
                // Sloty stylu se slucuji: co mam v telefonu, zustane; styl ze zalohy se ulozi na svoje misto,
                // je-li volne, jinak na jiny volny slot. Na co nezbylo misto, ceka na volbu uzivatele.
                // Starsi zaloha bez slotu stylu nic nepridava.
                self.pending_styles.clear();
                if v.get("style_slots").is_none() {
                    s.style_slots = self.settings.style_slots.clone();
                } else {
                    let (merged, left) = merge_style_slots(&self.settings.style_slots, &s.style_slots);
                    s.style_slots = merged;
                    self.pending_styles = left;
                }
                self.set_settings(s);
            }
        }
        let n = self.user.import(&data.user);
        self.storage.replace_user(&self.user.export());
        for c in &data.corrections {
            if c.bad.is_empty() || c.count == 0 || c.bad.chars().count() > 64 || c.good.chars().count() > 64 {
                continue;
            }
            let count = self.user.merge_correction(&c.bad, &c.good, c.count);
            self.storage.put_correction(&c.bad, &c.good, count);
        }
        let mut known: Vec<(String, String, String)> =
            self.macros.iter().map(|m| (m.name.clone(), m.text.clone(), m.shortcut.clone())).collect();
        for m in &data.macros {
            let Some(mut m) = m.clone().normalized() else { continue };
            m.id = 0;
            let triple = (m.name.clone(), m.text.clone(), m.shortcut.clone());
            if !known.contains(&triple) && self.storage.macro_save(&m) {
                known.push(triple);
            }
        }
        self.macros = self.storage.macro_list();
        let mut known_voice: Vec<(String, String, String)> =
            self.voice.iter().map(|c| (c.phrase.clone(), c.action.clone(), c.text.clone())).collect();
        for c in &data.voice {
            let Some(mut c) = c.clone().normalized() else { continue };
            c.id = 0;
            let triple = (c.phrase.clone(), c.action.clone(), c.text.clone());
            if !known_voice.contains(&triple) && self.storage.voice_save(&c) {
                known_voice.push(triple);
            }
        }
        self.voice = self.storage.voice_list();
        // Zaznam tahu: pridaji se jen radky, ktere tam jeste nejsou (cely radek se porovnava).
        let mut known_swipes = self.storage.swipe_log_list();
        for t in &data.swipe_log {
            let sane = (0..=2).contains(&t.outcome) && t.points.len() % 3 == 0 && t.points.iter().all(|v| v.is_finite());
            if sane && !known_swipes.contains(t) {
                self.storage.swipe_log_add_trace(t);
                known_swipes.push(t.clone());
            }
        }
        let mut cal = swipe::Calibration::default();
        let mut any = false;
        for k in &data.calibration {
            if k.key < 26 && k.n > 0 && k.sx.is_finite() && k.sy.is_finite() {
                cal.sum[k.key as usize] = (k.sx, k.sy);
                cal.n[k.key as usize] = k.n;
                any = true;
            }
        }
        if any {
            self.calibration = cal;
            self.storage.clear_calibration();
            self.storage.save_calibration(&self.calibration);
        }
        // Pripnute polozky schranky: pridaji se ty, ktere tam jeste nejsou (nejstarsi prvni, aby poradi zustalo).
        for (i, t) in data.pinned_clips.iter().enumerate().rev() {
            self.storage.clip_add_pinned(t, data.created + (data.pinned_clips.len() - i) as i64);
        }
        n as i64
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

/// Slouci sloty stylu z prave obnovene zalohy (`imported`) s tema v telefonu (`current`).
/// Slot je volny, kdyz se rovna vychozimu. Styl ze zalohy se ulozi na svoje cislo, je-li tam volno
/// (nebo uz stejny styl je), jinak na prvni volny slot; styl, pro ktery uz neni misto, se vrati v druhem poli.
fn merge_style_slots(current: &serde_json::Value, imported: &serde_json::Value) -> (serde_json::Value, Vec<serde_json::Value>) {
    let defaults = Settings::default().style_slots;
    let (Some(def), Some(cur), Some(imp)) = (defaults.as_array(), current.as_array(), imported.as_array()) else {
        return (current.clone(), Vec::new());
    };
    let mut out: Vec<serde_json::Value> = cur.clone();
    let free = |out: &[serde_json::Value], k: usize| out.get(k) == def.get(k);
    let mut waiting: Vec<usize> = Vec::new();
    // 1. pruchod: kazdy styl na svoje misto (nebo uz tam stejny je).
    for j in 0..imp.len().min(settings::STYLE_SLOTS) {
        let slot = &imp[j];
        if Some(slot) == def.get(j) || out.contains(slot) {
            continue;
        }
        if free(&out, j) {
            out[j] = slot.clone();
        } else {
            waiting.push(j);
        }
    }
    // 2. pruchod: kolidujici styly na prvni volny slot, jinak do fronty.
    let mut left = Vec::new();
    for j in waiting {
        let slot = &imp[j];
        if out.contains(slot) {
            continue;
        }
        match (0..out.len()).find(|&k| free(&out, k)) {
            Some(k) => out[k] = slot.clone(),
            None => left.push(slot.clone()),
        }
    }
    (serde_json::Value::Array(out), left)
}

/// Index slotu stylu z JNI cisla; mimo rozsah None.
fn style_index(slot: i64) -> Option<usize> {
    usize::try_from(slot).ok().filter(|&i| i < settings::STYLE_SLOTS)
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
        Some(e) => {
            let out = e.swipe(raw, flags & FLAG_CAPITALIZE != 0, now_ms());
            if flags & FLAG_PREVIEW != 0 {
                e.discard_swipe_pending();
            }
            lines(out)
        }
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
            // Volitelne dalsich ROW_OFFSET_SLOTS cisel = posuny radku z editoru (misto ulozenych).
            let offs = (f.len() >= 3 + layout::ROW_OFFSET_SLOTS).then(|| &f[3..3 + layout::ROW_OFFSET_SLOTS]);
            e.layout_with_offsets(f[0], f[1], f[2] as u8, num == 0, offs).to_json().into_bytes()
        }
        OP_SUGGEST => {
            let limit = if num & FLAG_MORE != 0 { predict::MAX_SUGGESTIONS + 6 } else { predict::MAX_SUGGESTIONS };
            lines(e.suggest_n(&sa, &sb, num & FLAG_CAPITALIZE != 0, now, limit))
        }
        OP_LEARN => bool_bytes(e.learn(&sa, &sb, num & FLAG_FROM_SWIPE != 0, now)),
        OP_USER_ADD => bool_bytes(e.add_user_word(&sa, now)),
        OP_MACRO_LIST => serde_json::to_vec(e.macros()).unwrap_or_default(),
        // Odpoved: id ulozeneho makra (nebo "0" pri neuspechu).
        OP_MACRO_SAVE => e.save_macro_id(&sa).to_string().into_bytes(),
        OP_MACRO_DELETE => {
            e.delete_macro(num);
            Vec::new()
        }
        OP_VOICE_LIST => serde_json::to_vec(e.voice_commands()).unwrap_or_default(),
        OP_VOICE_SAVE => bool_bytes(e.save_voice(&sa)),
        OP_VOICE_DELETE => {
            e.delete_voice(num);
            Vec::new()
        }
        OP_VOICE_SPLIT => e.voice_split(&sa).into_bytes(),
        OP_SHORTCUT => e.expand_shortcut(&sa).as_bytes().to_vec(),
        OP_AUTO_ACCENT => e.auto_accent(&sa, now).into_bytes(),
        OP_CORRECTION => {
            e.note_correction(&sa, &sb);
            Vec::new()
        }
        OP_CALIBRATION_RESET => {
            e.reset_calibration();
            Vec::new()
        }
        OP_SWIPE_LOG_EXPORT => e.export_swipe_log().into_bytes(),
        OP_SWIPE_LOG_CLEAR => {
            e.clear_swipe_log();
            Vec::new()
        }
        OP_SWIPE_LOG_COUNT => e.swipe_log_count().to_string().into_bytes(),
        OP_BACKUP_EXPORT => e.export_backup(now).into_bytes(),
        OP_BACKUP_IMPORT => e.import_backup(&sa).to_string().into_bytes(),
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
            bool_bytes(e.storage.clip_pin(num, sa.trim() == "1"))
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
        OP_STYLE_SAVE => {
            e.save_style(num);
            e.settings().to_json().into_bytes()
        }
        OP_PENDING_STYLES => serde_json::to_vec(e.pending_styles()).unwrap_or_default(),
        OP_PLACE_STYLE => {
            e.place_pending_style(num);
            e.settings().to_json().into_bytes()
        }
        OP_DISCARD_STYLES => {
            e.discard_pending_styles();
            Vec::new()
        }
        OP_STYLE_LOAD => {
            e.load_style(num);
            e.settings().to_json().into_bytes()
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

    #[test]
    fn style_slots_save_load_and_backup() {
        let mut e = Engine::with_storage(Storage::in_memory(), NOW);
        let mut s = e.settings().clone();
        s.theme = "custom".into();
        s.custom_background = "#123456".into();
        s.key_radius_dp = 20;
        s.language = "en".into();
        e.set_settings(s);
        e.save_style(2);
        let slot = e.settings().style_slots[2].clone();
        assert_eq!(slot["custom_background"], "#123456");
        assert_eq!(slot["key_radius_dp"], 20);
        assert!(slot.get("language").is_none());
        e.load_style(0);
        assert_eq!(e.settings().theme.as_str(), "dark");
        assert_eq!(e.settings().language, "en");
        e.load_style(2);
        assert_eq!((e.settings().custom_background.as_str(), e.settings().key_radius_dp), ("#123456", 20));
        assert_eq!(e.settings().language, "en");
        // spatny index nic nemeni
        let before = e.settings().clone();
        e.save_style(9);
        e.load_style(-1);
        assert_eq!(e.settings(), &before);
        // nova zaloha sloty prenese
        let json = e.export_backup(NOW);
        let mut b = Engine::with_storage(Storage::in_memory(), NOW);
        assert!(b.import_backup(&json) >= 0);
        assert_eq!(b.settings().style_slots, e.settings().style_slots);
    }

    /// Engine s ulozenym vzhledem (polomer `radius`) ve slotu `slot`.
    fn engine_with_style(slot: i64, radius: u32) -> Engine {
        let mut e = Engine::with_storage(Storage::in_memory(), NOW);
        let mut s = e.settings().clone();
        s.key_radius_dp = radius;
        e.set_settings(s);
        e.save_style(slot);
        e
    }

    #[test]
    fn layout_uses_editor_offsets_first_then_saved_ones() {
        let mut e = Engine::with_storage(Storage::in_memory(), NOW);
        let edit = e.layout_with_offsets(1440.0, 1000.0, 0, false, Some(&[0.0, 0.0, 0.2, 0.0, -0.1]));
        assert!((edit.row_offsets[2] - 0.2).abs() < 1e-6);
        // ulozene posuny se pouziji, kdyz editor zadne nedodal
        let mut s = e.settings().clone();
        s.row_offsets = vec![0.0, 0.0, 0.15, 0.0, 0.0];
        e.set_settings(s);
        let plain = e.layout(1440.0, 1000.0, 0, false);
        assert!((plain.row_offsets[2] - 0.15).abs() < 1e-6);
        assert!(plain.to_json().contains("row_offsets"));
    }

    #[test]
    fn import_keeps_existing_style_and_adds_other_slot() {
        // v telefonu je styl na slotu 1, zaloha ma styl na slotu 3
        let mut phone = engine_with_style(0, 12);
        let mine = phone.settings().style_slots[0].clone();
        let backup = engine_with_style(2, 18).export_backup(NOW);
        assert!(phone.import_backup(&backup) >= 0);
        let slots = phone.settings().style_slots.clone();
        assert_eq!(slots[0], mine);
        assert_eq!(slots[2]["key_radius_dp"], 18);
        assert!(phone.pending_styles().is_empty());
    }

    #[test]
    fn import_to_taken_slot_goes_to_first_free_slot() {
        let mut phone = engine_with_style(2, 12);
        let mine = phone.settings().style_slots[2].clone();
        let backup = engine_with_style(2, 18).export_backup(NOW);
        assert!(phone.import_backup(&backup) >= 0);
        let slots = phone.settings().style_slots.clone();
        assert_eq!(slots[2], mine, "puvodni styl zustane");
        // prvni volny slot je 1 (index 0), ten se rovna vychozimu
        assert_eq!(slots[0]["key_radius_dp"], 18);
        assert!(phone.pending_styles().is_empty());
    }

    #[test]
    fn import_of_identical_style_is_not_duplicated() {
        let mut phone = engine_with_style(1, 15);
        let before = phone.settings().style_slots.clone();
        let backup = engine_with_style(1, 15).export_backup(NOW);
        assert!(phone.import_backup(&backup) >= 0);
        assert_eq!(phone.settings().style_slots, before);
        assert!(phone.pending_styles().is_empty());
    }

    #[test]
    fn import_with_all_slots_taken_waits_for_choice() {
        let mut phone = Engine::with_storage(Storage::in_memory(), NOW);
        for i in 0..settings::STYLE_SLOTS {
            let mut s = phone.settings().clone();
            s.key_radius_dp = 11 + i as u32;
            phone.set_settings(s);
            phone.save_style(i as i64);
        }
        let full = phone.settings().style_slots.clone();
        let backup = engine_with_style(0, 21).export_backup(NOW);
        assert!(phone.import_backup(&backup) >= 0);
        assert_eq!(phone.settings().style_slots, full, "nic se neprepsalo");
        assert_eq!(phone.pending_styles().len(), 1);
        // volba: nahradit slot 4 (index 3)
        phone.place_pending_style(3);
        assert_eq!(phone.settings().style_slots[3]["key_radius_dp"], 21);
        assert_eq!(phone.settings().style_slots[0], full[0]);
        assert!(phone.pending_styles().is_empty());
        // preskoceni: fronta se zahodi
        assert!(phone.import_backup(&backup) >= 0);
        phone.discard_pending_styles();
        assert!(phone.pending_styles().is_empty());
    }

    #[test]
    fn old_backup_without_slots_keeps_current_slots() {
        let mut e = Engine::with_storage(Storage::in_memory(), NOW);
        let mut s = e.settings().clone();
        s.key_radius_dp = 21;
        e.set_settings(s);
        e.save_style(4);
        let saved = e.settings().style_slots.clone();
        assert_ne!(saved, Settings::default().style_slots);
        // zaloha ze starsi verze: nastaveni bez style_slots
        let old = r#"{"app":"reallyFAST","version":1,"settings":{"theme":"light","key_radius_dp":5}}"#;
        assert!(e.import_backup(old) >= 0);
        assert_eq!(e.settings().theme.as_str(), "light");
        assert_eq!(e.settings().style_slots, saved);
        // nova instalace + stara zaloha -> vychozi sloty
        let mut f = Engine::with_storage(Storage::in_memory(), NOW);
        assert!(f.import_backup(old) >= 0);
        assert_eq!(f.settings().style_slots, Settings::default().style_slots);
    }

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
    fn saving_a_macro_returns_its_id() {
        let mut e = Engine::with_storage(Storage::in_memory(), NOW);
        // id ulozeneho makra: nove dostane id > 0, uprava vraci stejne id, neuspech 0
        let id = e.save_macro_id(r#"{"id":0,"name":"Druhe","text":"text","shortcut":"dr"}"#);
        assert!(id > 0);
        assert_eq!(e.macros().iter().find(|m| m.name == "Druhe").map(|m| m.id), Some(id));
        assert_eq!(e.save_macro_id(&format!(r#"{{"id":{id},"name":"Druhe","text":"jiny","shortcut":"dr"}}"#)), id);
        assert_eq!(e.save_macro_id("nonsense"), 0);
        // operace vraci id jako text, "0" pri neuspechu
        let reply = dispatch(OP_MACRO_SAVE, br#"{"id":0,"name":"Treti","text":"x"}"#, b"", 0);
        assert!(String::from_utf8(reply).unwrap().parse::<i64>().unwrap() > 0);
        assert_eq!(dispatch(OP_MACRO_SAVE, b"nonsense", b"", 0), b"0");
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
    fn voice_commands_save_split_delete() {
        let mut e = engine_with_dict();
        assert!(e.save_voice(r#"{"id":0,"phrase":"Nový řádek","action":"newline"}"#));
        assert!(e.save_voice(r#"{"id":0,"phrase":"tečka","action":"text","text":"."}"#));
        assert!(!e.save_voice(r#"{"id":0,"phrase":"x","action":"text","text":" "}"#));
        assert!(!e.save_voice(r#"{"id":0,"phrase":"x","action":"nope"}"#));
        assert!(!e.save_voice("nonsense"));
        assert_eq!(e.voice_commands().len(), 2);
        let v: serde_json::Value = serde_json::from_str(&e.voice_split("ahoj novy radek konec tecka")).unwrap();
        let acts: Vec<&str> = v.as_array().unwrap().iter().map(|s| s["action"].as_str().unwrap()).collect();
        assert_eq!(acts, vec!["", "newline", "", "text"]);
        assert_eq!(v[3]["text"], ".");
        let id = e.voice_commands()[0].id;
        e.delete_voice(id);
        assert_eq!(e.voice_commands().len(), 1);
        assert_eq!(e.voice_split("novy radek"), r#"[{"action":"","text":"novy radek"}]"#);
    }

    #[test]
    fn key_map_changes_layout_and_swipe() {
        let mut e = engine_with_dict();
        let mut s = e.settings().clone();
        s.key_map.insert("a".into(), "b".into());
        s.key_map.insert("b".into(), "a".into());
        e.set_settings(s);
        let l = e.layout(1440.0, 1000.0, MODE_LETTERS, true);
        let k = l.keys.iter().find(|k| k.src == 'a' as i32).unwrap();
        assert_eq!(k.code, 'b' as i32);
        // swipe po puvodnich pozicich b-h-o-j se po premapovani cte jako "ahoj"
        let plain = Layout::build(1440.0, 1000.0, true, MODE_LETTERS, true, "x").geom();
        let path = swipe::ideal_path("bhoj", &plain, 12);
        assert_eq!(e.swipe(&path, false, NOW)[0], "ahoj");
        // vynulovani vrati puvodni rozlozeni
        let mut s = e.settings().clone();
        s.key_map.clear();
        e.set_settings(s);
        let l = e.layout(1440.0, 1000.0, MODE_LETTERS, true);
        assert_eq!(l.key_for_code('a' as i32).unwrap().src, 'a' as i32);
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

    fn macro_triples(e: &Engine) -> Vec<(String, String, String)> {
        e.macros().iter().map(|m| (m.name.clone(), m.text.clone(), m.shortcut.clone())).collect()
    }

    fn voice_triples(e: &Engine) -> Vec<(String, String, String)> {
        e.voice_commands().iter().map(|c| (c.phrase.clone(), c.action.clone(), c.text.clone())).collect()
    }

    #[test]
    fn backup_round_trip_into_fresh_engine() {
        let mut a = engine_with_dict();
        let mut s = a.settings().clone();
        s.theme = "light".into();
        s.language = "cs".into();
        s.vibration_ms = 33;
        s.swipe_log = true;
        a.set_settings(s);
        a.learn("Novák", "pan Jan ", false, NOW);
        a.learn("pes", "ahoj dobrý ", false, NOW);
        a.note_correction("mas", "máš");
        a.note_correction("blbost", "");
        assert!(a.save_macro(r#"{"id":0,"name":"Pozdrav","text":"S pozdravem","shortcut":"mfg"}"#));
        assert!(a.save_macro(r#"{"id":0,"name":"Mail","text":"jan@example.com"}"#));
        assert!(a.save_voice(r#"{"id":0,"phrase":"smazat","action":"delete_word"}"#));
        assert!(a.save_voice(r#"{"id":0,"phrase":"tečka","action":"text","text":"."}"#));
        for _ in 0..10 {
            a.calibration.add(0, 0.2, -0.1);
            a.calibration.add(3, -0.05, 0.1);
        }
        let geom = a.layout(1440.0, 1000.0, MODE_LETTERS, true).geom();
        for i in 0..2 {
            let t = storage::SwipeTrace {
                ts: 100 + i,
                word: format!("slovo{i}"),
                first: "prvni".into(),
                outcome: i % 3,
                geom,
                points: vec![1.5, 2.5, 0.0, 3.0, 4.0, 16.0],
            };
            a.storage.swipe_log_add_trace(&t);
        }
        let json = a.export_backup(NOW);
        assert!(json.contains("\"app\": \"reallyFAST\""));
        assert!(!json.contains("\"clipboard\":") && !json.contains("\"emoji\""));

        let mut b = Engine::with_storage(Storage::in_memory(), NOW);
        assert_eq!(b.import_backup(&json), a.user().len() as i64);
        assert_eq!(b.settings(), a.settings());
        assert_eq!(b.user().export(), a.user().export());
        assert!(!b.user().export().bigrams.is_empty() && !b.user().export().trigrams.is_empty());
        assert_eq!(b.user().corrections_list(), a.user().corrections_list());
        assert_eq!(b.user().corrections_list().len(), 2);
        assert_eq!(macro_triples(&b), macro_triples(&a));
        assert_eq!(b.macros().len(), 2);
        assert_eq!(voice_triples(&b), voice_triples(&a));
        assert_eq!(b.voice_commands().len(), 2);
        assert_eq!(b.calibration.sum, a.calibration.sum);
        assert_eq!(b.calibration.n, a.calibration.n);
        // vse je i v uloziste, ne jen v pameti
        assert_eq!(b.storage.load_user().export(), a.user().export());
        assert_eq!(b.storage.load_user().corrections_list(), a.user().corrections_list());
        assert_eq!(b.storage.macro_list(), b.macros());
        assert_eq!(b.storage.voice_list(), b.voice_commands());
        assert_eq!(b.storage.load_calibration().n, a.calibration.n);
        // zaznam tahu se obnovi a dalsi import ho nezdvoji
        assert_eq!(b.storage.swipe_log_list(), a.storage.swipe_log_list());
        assert_eq!(b.swipe_log_count(), 2);
        b.import_backup(&json);
        assert_eq!(b.swipe_log_count(), 2);
        // starsi zaloha bez swipe_log se nacte a zaznam nechava byt
        b.import_backup(r#"{"app":"reallyFAST"}"#);
        assert_eq!(b.swipe_log_count(), 2);
    }

    #[test]
    fn backup_import_is_idempotent() {
        let mut a = engine_with_dict();
        a.learn("Novák", "pan Jan ", false, NOW);
        a.learn("Novák", "pan Jan ", false, NOW);
        a.note_correction("mas", "máš");
        a.note_correction("mas", "máš");
        assert!(a.save_macro(r#"{"id":0,"name":"Pozdrav","text":"S pozdravem","shortcut":"mfg"}"#));
        assert!(a.save_voice(r#"{"id":0,"phrase":"smazat","action":"delete_word"}"#));
        for _ in 0..8 {
            a.calibration.add(5, 0.1, 0.1);
        }
        let json = a.export_backup(NOW);
        let mut b = engine_with_dict();
        let n = b.import_backup(&json);
        assert_eq!(n, 1);
        let once = b.export_backup(NOW);
        assert_eq!(b.import_backup(&json), n);
        assert_eq!(b.export_backup(NOW), once);
        assert_eq!(b.user().get("Novák").unwrap().count, 2);
        assert_eq!(b.macros().len(), 1);
        assert_eq!(b.storage.macro_list().len(), 1);
        assert_eq!(b.voice_commands().len(), 1);
        assert_eq!(b.storage.voice_list().len(), 1);
        // slouceni: vyssi pocet vyhrava, existujici makra se nezdvojuji
        b.learn("Novák", "pan Jan ", false, NOW);
        assert_eq!(b.import_backup(&json), n);
        assert_eq!(b.user().get("Novák").unwrap().count, 3);
    }

    #[test]
    fn backup_rejects_invalid_and_keeps_missing_parts() {
        let mut e = engine_with_dict();
        let mut s = e.settings().clone();
        s.theme = "light".into();
        e.set_settings(s);
        e.learn("ahoj", "", false, NOW);
        assert_eq!(e.import_backup("not json"), -1);
        assert_eq!(e.import_backup(r#"{"app":"jina-aplikace","settings":{"theme":"dark"}}"#), -1);
        assert_eq!(e.import_backup("{}"), -1);
        assert_eq!(e.import_backup(r#"[1,2]"#), -1);
        assert_eq!(e.settings().theme, "light");
        // castecny soubor: bez nastaveni a prizpusobeni zustava aktualni stav
        e.calibration.add(1, 0.1, 0.1);
        assert_eq!(e.import_backup(r#"{"app":"reallyFAST","user":{"words":[{"word":"nove","count":2,"last_used":5}]}}"#), 1);
        assert_eq!(e.settings().theme, "light");
        assert_eq!(e.calibration.n[1], 1);
        assert_eq!(e.user().get("nove").unwrap().count, 2);
        assert!(e.user().get("ahoj").is_some());
        // nastaveni ze souboru projde validaci
        assert_eq!(e.import_backup(r#"{"app":"reallyFAST","settings":{"key_height_dp":999,"theme":"pink"}}"#), 0);
        assert_eq!(e.settings().key_height_dp, 72);
        assert_eq!(e.settings().theme, "dark");
    }

    fn logging_engine(log: bool) -> (Engine, Vec<f32>, KeyGeom) {
        let mut e = engine_with_dict();
        let mut s = e.settings().clone();
        s.swipe_log = log;
        e.set_settings(s);
        let l = e.layout(1440.0, 1000.0, MODE_LETTERS, true);
        let g = l.geom();
        let path = swipe::ideal_path("ahoj", &g, 12);
        (e, path, g)
    }

    #[test]
    fn swipe_log_is_off_by_default() {
        assert!(!Settings::default().swipe_log);
        let (mut e, path, _) = logging_engine(false);
        assert_eq!(e.swipe(&path, false, NOW)[0], "ahoj");
        e.learn("ahoj", "", true, NOW);
        e.swipe(&path, false, NOW);
        e.note_correction("ahoj", "");
        assert_eq!(e.swipe_log_count(), 0);
    }

    #[test]
    fn swipe_log_records_outcomes() {
        let (mut e, path, g) = logging_engine(true);
        // prijato prvni navrh (velikost pismen se nerozlisuje)
        assert_eq!(e.swipe(&path, true, NOW)[0], "Ahoj");
        assert!(e.learn("Ahoj", "", true, NOW));
        // vybrana alternativa
        e.swipe(&path, false, NOW);
        e.learn("den", "", true, NOW);
        // smazano hned po swipe
        e.swipe(&path, false, NOW);
        e.note_correction("ahoj", "");
        let list = e.storage.swipe_log_list();
        assert_eq!(list.len(), 3);
        assert_eq!((list[0].outcome, list[0].word.as_str(), list[0].first.as_str()), (0, "Ahoj", "ahoj"));
        assert_eq!((list[1].outcome, list[1].word.as_str(), list[1].first.as_str()), (1, "den", "ahoj"));
        assert_eq!((list[2].outcome, list[2].word.as_str(), list[2].first.as_str()), (2, "", "ahoj"));
        // body a geometrie jsou presne ty, ktere sel prehrat
        assert_eq!(list[0].points, path);
        assert_eq!(list[0].geom.centers, g.centers);
        assert_eq!(list[0].geom.present, g.present);
        assert_eq!(e.swipe_log_count(), 3);
        // export ma ocekavany tvar a jde nacist zpet
        let v: serde_json::Value = serde_json::from_str(&e.export_swipe_log()).unwrap();
        assert_eq!(v["app"], "reallyFAST");
        assert_eq!(v["version"], 1);
        assert_eq!(v["swipes"].as_array().unwrap().len(), 3);
        assert_eq!(v["swipes"][1]["outcome"], 1);
        let file: storage::SwipeLogFile = serde_json::from_str(&e.export_swipe_log()).unwrap();
        assert_eq!(file.swipes[0].points, path);
        e.clear_swipe_log();
        assert_eq!(e.swipe_log_count(), 0);
    }

    #[test]
    fn preview_swipe_is_not_logged() {
        let (mut e, path, _) = logging_engine(true);
        // zkusebni tah z nahledu se zahodi; pozdejsi potvrzeni slova ho nezapise
        e.swipe(&path, false, NOW);
        e.discard_swipe_pending();
        e.learn("ahoj", "", true, NOW);
        assert_eq!(e.swipe_log_count(), 0);
    }

    #[test]
    fn swipe_log_never_logs_twice_and_ignores_typing() {
        let (mut e, path, _) = logging_engine(true);
        e.swipe(&path, false, NOW);
        e.learn("ahoj", "", false, NOW); // psano klavesami, ne ze swipe
        assert_eq!(e.swipe_log_count(), 0);
        e.learn("ahoj", "", true, NOW);
        e.learn("ahoj", "", true, NOW);
        e.note_correction("ahoj", "");
        assert_eq!(e.swipe_log_count(), 1);
        // oprava na jine slovo nic nezapisuje, zapise az nasledne ucici volani
        e.swipe(&path, false, NOW);
        e.note_correction("ahoj", "den");
        assert_eq!(e.swipe_log_count(), 1);
        e.learn("den", "", true, NOW);
        assert_eq!(e.swipe_log_count(), 2);
        assert_eq!(e.storage.swipe_log_list()[1].outcome, 1);
        // swipe bez vysledku (kratky tah) si nic nepamatuje
        e.swipe(&[100.0, 100.0, 0.0, 110.0, 104.0, 30.0], false, NOW);
        e.learn("ahoj", "", true, NOW);
        assert_eq!(e.swipe_log_count(), 2);
    }

    #[test]
    fn swipe_log_works_with_learning_off() {
        let (mut e, path, _) = logging_engine(true);
        let mut s = e.settings().clone();
        s.learn_from_typing = false;
        e.set_settings(s);
        e.swipe(&path, false, NOW);
        assert!(!e.learn("ahoj", "", true, NOW));
        e.swipe(&path, false, NOW);
        e.note_correction("ahoj", "");
        assert_eq!(e.swipe_log_count(), 2);
        assert!(e.user().is_empty());
        assert_eq!(e.user().rejections("ahoj"), 0);
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
        assert!(s.contains("\"language\":\"cs\""));
        assert_eq!(dispatch(OP_DELETE_WORD_LEN, "ahoj světe".as_bytes(), b"", 0), b"5");
        assert_eq!(dispatch(OP_SHOULD_CAPITALIZE, b"Ahoj. ", b"", 0), b"1");
        assert_eq!(dispatch(OP_GESTURE, b"2,-200,0,100,140,150", b"", 0), b"1");
        assert_eq!(dispatch(OP_VOICE_SAVE, br#"{"id":0,"phrase":"smazat","action":"delete_word"}"#, b"", 0), b"1");
        assert_eq!(dispatch(OP_VOICE_SAVE, br#"{"id":0,"phrase":"","action":"enter"}"#, b"", 0), b"0");
        let vl: serde_json::Value = serde_json::from_slice(&dispatch(OP_VOICE_LIST, b"", b"", 0)).unwrap();
        assert_eq!(vl[0]["phrase"], "smazat");
        let sp: serde_json::Value = serde_json::from_slice(&dispatch(OP_VOICE_SPLIT, "ahoj smazat".as_bytes(), b"", 0)).unwrap();
        assert_eq!(sp[1]["action"], "delete_word");
        let vid = vl[0]["id"].as_i64().unwrap();
        dispatch(OP_VOICE_DELETE, b"", b"", vid);
        assert_eq!(dispatch(OP_VOICE_LIST, b"", b"", 0), b"[]");
        assert_eq!(dispatch(OP_SWIPE_LOG_COUNT, b"", b"", 0), b"0");
        assert!(String::from_utf8(dispatch(OP_SWIPE_LOG_EXPORT, b"", b"", 0)).unwrap().contains("\"swipes\""));
        dispatch(OP_SWIPE_LOG_CLEAR, b"", b"", 0);
        let backup = dispatch(OP_BACKUP_EXPORT, b"", b"", 0);
        assert!(String::from_utf8_lossy(&backup).contains("\"reallyFAST\""));
        assert_eq!(dispatch(OP_BACKUP_IMPORT, &backup, b"", 0), b"1");
        assert_eq!(dispatch(OP_BACKUP_IMPORT, b"nonsense", b"", 0), b"-1");
        assert!(dispatch(9999, b"", b"", 0).is_empty());
        assert!(swipe_global(&[f32::NAN; 9], 0).is_empty());

        *engine() = None; // zavrit databazi pred smazanim adresare
        let _ = std::fs::remove_dir_all(&dir);
    }
}
