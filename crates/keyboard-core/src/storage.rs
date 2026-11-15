//! Uloziste: `settings.json` (serde) + SQLite `reallyfast.db` (osobni slovnik, bigramy,
//! schranka, nedavne emoji) + export/import osobniho slovniku jako JSON.
//!
//! SIFROVANI: databaze neni sifrovana na urovni aplikace. Duvod: SQLCipher vyzaduje
//! krizovou kompilaci OpenSSL pro Android a ChaCha20Poly1305 dalsi kryptografickou
//! zavislost mimo povoleny seznam (serde, serde_json, rusqlite, rayon, fst, once_cell).
//! Data lezi v privatnim adresari aplikace (/data/data/cz.reallyfast/files), ktery je
//! chranen sandboxem Androidu a na S25 Ultra sifrovan systemovym file-based encryption
//! (klice v hardwarovem Keystore). Aplikace nema opravneni INTERNET a zalohy jsou vypnute
//! (allowBackup=false), takze data zarizeni neopusti a pri odinstalaci se smazou.

use crate::clipboard::{self, ClipItem};
use crate::layout::KeyGeom;
use crate::learning::{Export, UserDict};
use crate::macros::Macro;
use crate::swipe::Calibration;
use crate::voice::VoiceCommand;
use crate::settings::Settings;
use rusqlite::{params, Connection};
use serde::{Deserialize, Serialize};
use std::fs;
use std::io;
use std::path::{Path, PathBuf};

const SETTINGS_FILE: &str = "settings.json";
const DB_FILE: &str = "reallyfast.db";
const EMOJI_RECENT_MAX: usize = 32;
/// Nejvyse tolik tahu se drzi v zaznamu (nejstarsi se mazou).
pub const SWIPE_LOG_MAX: i64 = 1000;

const SCHEMA: &str = "
CREATE TABLE IF NOT EXISTS user_words (
    word TEXT PRIMARY KEY, count INTEGER NOT NULL, last_used INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS user_bigrams (
    w1 TEXT NOT NULL, w2 TEXT NOT NULL, count INTEGER NOT NULL, PRIMARY KEY (w1, w2));
CREATE TABLE IF NOT EXISTS clipboard (
    id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT NOT NULL UNIQUE,
    created INTEGER NOT NULL, pinned INTEGER NOT NULL DEFAULT 0);
CREATE TABLE IF NOT EXISTS emoji_recent (
    emoji TEXT PRIMARY KEY, last_used INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS user_trigrams (
    ctx TEXT NOT NULL, w TEXT NOT NULL, count INTEGER NOT NULL, PRIMARY KEY (ctx, w));
CREATE TABLE IF NOT EXISTS corrections (
    bad TEXT NOT NULL, good TEXT NOT NULL, count INTEGER NOT NULL, PRIMARY KEY (bad, good));
CREATE TABLE IF NOT EXISTS macros (
    id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, text TEXT NOT NULL,
    shortcut TEXT NOT NULL DEFAULT '');
CREATE TABLE IF NOT EXISTS voice_commands (
    id INTEGER PRIMARY KEY AUTOINCREMENT, phrase TEXT NOT NULL, action TEXT NOT NULL,
    text TEXT NOT NULL DEFAULT '');
CREATE TABLE IF NOT EXISTS key_calibration (
    key INTEGER PRIMARY KEY, sx REAL NOT NULL, sy REAL NOT NULL, n INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS swipe_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, word TEXT NOT NULL,
    first TEXT NOT NULL, outcome INTEGER NOT NULL, geom TEXT NOT NULL, points TEXT NOT NULL);
";

/// Jeden zaznamenany tah. `outcome`: 0 = prijato prvni navrh, 1 = vybrana alternativa,
/// 2 = slovo hned smazano. `points` jsou syrove trojice (x, y, t) tak, jak prisly do decoderu.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct SwipeTrace {
    pub ts: i64,
    pub word: String,
    pub first: String,
    pub outcome: i64,
    pub geom: KeyGeom,
    pub points: Vec<f32>,
}

/// Soubor s exportem zaznamu tahu (vstup nastroje `tune`).
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default)]
pub struct SwipeLogFile {
    pub app: String,
    pub version: u32,
    pub swipes: Vec<SwipeTrace>,
}

pub struct Storage {
    dir: Option<PathBuf>,
    conn: Option<Connection>,
}

fn open_db(path: Option<&Path>) -> Option<Connection> {
    let conn = match path {
        Some(p) => Connection::open(p).ok()?,
        None => Connection::open_in_memory().ok()?,
    };
    // WAL + synchronous=NORMAL: zapis jednoho slova trva zlomky ms a neblokuje psani.
    let _ = conn.pragma_update(None, "journal_mode", "WAL");
    let _ = conn.pragma_update(None, "synchronous", "NORMAL");
    conn.execute_batch(SCHEMA).ok()?;
    Some(conn)
}

impl Storage {
    /// Otevre uloziste v adresari aplikace. Kdyz databaze nejde otevrit,
    /// klavesnice bezi dal jen s daty v pameti (zadny crash).
    pub fn open(dir: &Path) -> Storage {
        let _ = fs::create_dir_all(dir);
        Storage { dir: Some(dir.to_path_buf()), conn: open_db(Some(&dir.join(DB_FILE))) }
    }

    /// Uloziste jen v pameti (testy).
    pub fn in_memory() -> Storage {
        Storage { dir: None, conn: open_db(None) }
    }

    pub fn has_db(&self) -> bool {
        self.conn.is_some()
    }

    // ------------------------------------------------------------------ nastaveni

    /// Nacte nastaveni. Druha hodnota rika, zda soubor existoval a byl platny.
    pub fn load_settings(&self) -> (Settings, bool) {
        let loaded = self
            .dir
            .as_ref()
            .and_then(|d| fs::read_to_string(d.join(SETTINGS_FILE)).ok())
            .and_then(|json| Settings::from_json(&json));
        match loaded {
            Some(s) => (s, true),
            None => (Settings::default(), false),
        }
    }

    /// Atomicky zapis (docasny soubor + rename), aby se nastaveni neposkodilo pri padu.
    pub fn save_settings(&self, settings: &Settings) -> io::Result<()> {
        let Some(dir) = &self.dir else { return Ok(()) };
        let tmp = dir.join("settings.json.tmp");
        fs::write(&tmp, settings.to_json())?;
        fs::rename(&tmp, dir.join(SETTINGS_FILE))
    }

    // ------------------------------------------------------------------ osobni slovnik

    pub fn load_user(&self) -> UserDict {
        let mut user = UserDict::new();
        let Some(conn) = &self.conn else { return user };
        if let Ok(mut st) = conn.prepare("SELECT word, count, last_used FROM user_words") {
            if let Ok(rows) = st.query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, u32>(1)?, r.get::<_, i64>(2)?))) {
                for (w, c, t) in rows.flatten() {
                    user.insert_word(&w, c, t);
                }
            }
        }
        if let Ok(mut st) = conn.prepare("SELECT w1, w2, count FROM user_bigrams") {
            if let Ok(rows) = st.query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?, r.get::<_, u32>(2)?))) {
                for (a, b, c) in rows.flatten() {
                    user.insert_bigram(&a, &b, c);
                }
            }
        }
        if let Ok(mut st) = conn.prepare("SELECT ctx, w, count FROM user_trigrams") {
            if let Ok(rows) = st.query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?, r.get::<_, u32>(2)?))) {
                for (a, b, c) in rows.flatten() {
                    user.insert_trigram(&a, &b, c);
                }
            }
        }
        if let Ok(mut st) = conn.prepare("SELECT bad, good, count FROM corrections WHERE count > 0") {
            if let Ok(rows) = st.query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?, r.get::<_, u32>(2)?))) {
                for (a, b, c) in rows.flatten() {
                    user.insert_correction(&a, &b, c);
                }
            }
        }
        user
    }

    pub fn put_trigram(&self, ctx: &str, word: &str, count: u32) {
        if let Some(conn) = &self.conn {
            let _ = conn.execute(
                "INSERT INTO user_trigrams (ctx, w, count) VALUES (?1, ?2, ?3)
                 ON CONFLICT(ctx, w) DO UPDATE SET count = ?3",
                params![ctx, word, count],
            );
        }
    }

    /// Uceni z oprav; `good` prazdne = slovo bylo smazano.
    pub fn put_correction(&self, bad: &str, good: &str, count: u32) {
        if let Some(conn) = &self.conn {
            let _ = conn.execute(
                "INSERT INTO corrections (bad, good, count) VALUES (?1, ?2, ?3)
                 ON CONFLICT(bad, good) DO UPDATE SET count = ?3",
                params![bad, good, count],
            );
        }
    }

    pub fn put_word(&self, word: &str, count: u32, last_used: i64) {
        if let Some(conn) = &self.conn {
            let _ = conn.execute(
                "INSERT INTO user_words (word, count, last_used) VALUES (?1, ?2, ?3)
                 ON CONFLICT(word) DO UPDATE SET count = ?2, last_used = ?3",
                params![word, count, last_used],
            );
        }
    }

    pub fn put_bigram(&self, w1: &str, w2: &str, count: u32) {
        if let Some(conn) = &self.conn {
            let _ = conn.execute(
                "INSERT INTO user_bigrams (w1, w2, count) VALUES (?1, ?2, ?3)
                 ON CONFLICT(w1, w2) DO UPDATE SET count = ?3",
                params![w1, w2, count],
            );
        }
    }

    pub fn delete_word(&self, word: &str) {
        if let Some(conn) = &self.conn {
            let _ = conn.execute("DELETE FROM user_words WHERE word = ?1", params![word]);
            let _ = conn.execute("DELETE FROM user_trigrams WHERE w = ?1", params![word]);
            let _ = conn.execute("DELETE FROM corrections WHERE bad = ?1", params![word]);
            let _ = conn.execute(
                "DELETE FROM user_bigrams WHERE w2 = ?1 OR w1 = ?2",
                params![word, word.to_lowercase()],
            );
        }
    }

    pub fn clear_user(&self) {
        if let Some(conn) = &self.conn {
            let _ = conn.execute_batch(
                "DELETE FROM user_words; DELETE FROM user_bigrams; DELETE FROM user_trigrams; DELETE FROM corrections;",
            );
        }
    }

    /// Prepise cely osobni slovnik v jedne transakci (po importu).
    pub fn replace_user(&self, data: &Export) {
        let Some(conn) = &self.conn else { return };
        let run = || -> rusqlite::Result<()> {
            conn.execute_batch("BEGIN; DELETE FROM user_words; DELETE FROM user_bigrams; DELETE FROM user_trigrams;")?;
            {
                let mut st = conn.prepare("INSERT OR REPLACE INTO user_words (word, count, last_used) VALUES (?1, ?2, ?3)")?;
                for w in &data.words {
                    st.execute(params![w.word, w.count, w.last_used])?;
                }
                let mut st = conn.prepare("INSERT OR REPLACE INTO user_bigrams (w1, w2, count) VALUES (?1, ?2, ?3)")?;
                for b in &data.bigrams {
                    st.execute(params![b.w1, b.w2, b.count])?;
                }
                let mut st = conn.prepare("INSERT OR REPLACE INTO user_trigrams (ctx, w, count) VALUES (?1, ?2, ?3)")?;
                for t in &data.trigrams {
                    st.execute(params![t.w1, t.w2, t.count])?;
                }
            }
            conn.execute_batch("COMMIT;")
        };
        if run().is_err() {
            let _ = conn.execute_batch("ROLLBACK;");
        }
    }

    // ------------------------------------------------------------------ schranka

    /// Prida text do historie. Vraci false, kdyz se text neuklada (heslo, prazdny, moc dlouhy).
    pub fn clip_add(&self, text: &str, now: i64) -> bool {
        if !clipboard::is_storable(text) {
            return false;
        }
        let Some(conn) = &self.conn else { return false };
        let ok = conn
            .execute(
                "INSERT INTO clipboard (text, created, pinned) VALUES (?1, ?2, 0)
                 ON CONFLICT(text) DO UPDATE SET created = ?2",
                params![text, now],
            )
            .is_ok();
        // Limit 30 polozek: nejstarsi nepripnute pryc.
        let _ = conn.execute(
            "DELETE FROM clipboard WHERE pinned = 0 AND id NOT IN (
                 SELECT id FROM clipboard ORDER BY pinned DESC, created DESC, id DESC LIMIT ?1)",
            params![clipboard::MAX_ITEMS as i64],
        );
        ok
    }

    /// Smaze nepripnute polozky starsi nez `ttl_minutes`.
    pub fn clip_expire(&self, now: i64, ttl_minutes: u32) {
        if let Some(conn) = &self.conn {
            let cutoff = now - ttl_minutes as i64 * 60_000;
            let _ = conn.execute("DELETE FROM clipboard WHERE pinned = 0 AND created < ?1", params![cutoff]);
        }
    }

    /// Pripnute prvni, pak od nejnovejsich.
    pub fn clip_list(&self) -> Vec<ClipItem> {
        let Some(conn) = &self.conn else { return Vec::new() };
        let Ok(mut st) = conn.prepare(
            "SELECT id, text, created, pinned FROM clipboard ORDER BY pinned DESC, created DESC, id DESC LIMIT ?1",
        ) else {
            return Vec::new();
        };
        let rows = st.query_map(params![clipboard::MAX_ITEMS as i64], |r| {
            Ok(ClipItem { id: r.get(0)?, text: r.get(1)?, created: r.get(2)?, pinned: r.get::<_, i64>(3)? != 0 })
        });
        let out = match rows {
            Ok(rows) => rows.flatten().collect(),
            Err(_) => Vec::new(),
        };
        out
    }

    pub fn clip_pin(&self, id: i64, pinned: bool) {
        if let Some(conn) = &self.conn {
            let _ = conn.execute("UPDATE clipboard SET pinned = ?2 WHERE id = ?1", params![id, pinned as i64]);
        }
    }

    pub fn clip_delete(&self, id: i64) {
        if let Some(conn) = &self.conn {
            let _ = conn.execute("DELETE FROM clipboard WHERE id = ?1", params![id]);
        }
    }

    /// Smaze celou historii vcetne pripnutych.
    pub fn clip_clear(&self) {
        if let Some(conn) = &self.conn {
            let _ = conn.execute_batch("DELETE FROM clipboard;");
        }
    }

    // ------------------------------------------------------------------ makra

    pub fn macro_list(&self) -> Vec<Macro> {
        let Some(conn) = &self.conn else { return Vec::new() };
        let Ok(mut st) = conn.prepare("SELECT id, name, text, shortcut FROM macros ORDER BY id") else {
            return Vec::new();
        };
        let out = match st.query_map([], |r| {
            Ok(Macro { id: r.get(0)?, name: r.get(1)?, text: r.get(2)?, shortcut: r.get(3)? })
        }) {
            Ok(rows) => rows.flatten().collect(),
            Err(_) => Vec::new(),
        };
        out
    }

    /// Ulozi makro (id 0 = nove). Vraci false pri prekroceni limitu nebo chybe databaze.
    pub fn macro_save(&self, m: &Macro) -> bool {
        let Some(conn) = &self.conn else { return false };
        if m.id > 0 {
            conn.execute(
                "UPDATE macros SET name = ?2, text = ?3, shortcut = ?4 WHERE id = ?1",
                params![m.id, m.name, m.text, m.shortcut],
            )
            .is_ok()
        } else {
            let count: i64 = conn.query_row("SELECT COUNT(*) FROM macros", [], |r| r.get(0)).unwrap_or(0);
            if count as usize >= crate::macros::MAX_MACROS {
                return false;
            }
            conn.execute("INSERT INTO macros (name, text, shortcut) VALUES (?1, ?2, ?3)", params![m.name, m.text, m.shortcut])
                .is_ok()
        }
    }

    pub fn macro_delete(&self, id: i64) {
        if let Some(conn) = &self.conn {
            let _ = conn.execute("DELETE FROM macros WHERE id = ?1", params![id]);
        }
    }

    // ------------------------------------------------------------------ hlasove povely

    pub fn voice_list(&self) -> Vec<VoiceCommand> {
        let Some(conn) = &self.conn else { return Vec::new() };
        let Ok(mut st) = conn.prepare("SELECT id, phrase, action, text FROM voice_commands ORDER BY id") else {
            return Vec::new();
        };
        let out = match st.query_map([], |r| {
            Ok(VoiceCommand { id: r.get(0)?, phrase: r.get(1)?, action: r.get(2)?, text: r.get(3)? })
        }) {
            Ok(rows) => rows.flatten().collect(),
            Err(_) => Vec::new(),
        };
        out
    }

    /// Ulozi povel (id 0 = novy). Vraci false pri prekroceni limitu nebo chybe databaze.
    pub fn voice_save(&self, c: &VoiceCommand) -> bool {
        let Some(conn) = &self.conn else { return false };
        if c.id > 0 {
            conn.execute(
                "UPDATE voice_commands SET phrase = ?2, action = ?3, text = ?4 WHERE id = ?1",
                params![c.id, c.phrase, c.action, c.text],
            )
            .is_ok()
        } else {
            let count: i64 = conn.query_row("SELECT COUNT(*) FROM voice_commands", [], |r| r.get(0)).unwrap_or(0);
            if count as usize >= crate::voice::MAX_COMMANDS {
                return false;
            }
            conn.execute(
                "INSERT INTO voice_commands (phrase, action, text) VALUES (?1, ?2, ?3)",
                params![c.phrase, c.action, c.text],
            )
            .is_ok()
        }
    }

    pub fn voice_delete(&self, id: i64) {
        if let Some(conn) = &self.conn {
            let _ = conn.execute("DELETE FROM voice_commands WHERE id = ?1", params![id]);
        }
    }

    // ------------------------------------------------------------------ prizpusobeni swipe

    pub fn load_calibration(&self) -> Calibration {
        let mut cal = Calibration::default();
        let Some(conn) = &self.conn else { return cal };
        if let Ok(mut st) = conn.prepare("SELECT key, sx, sy, n FROM key_calibration") {
            if let Ok(rows) = st.query_map([], |r| Ok((r.get::<_, i64>(0)?, r.get::<_, f64>(1)?, r.get::<_, f64>(2)?, r.get::<_, u32>(3)?))) {
                for (k, sx, sy, n) in rows.flatten() {
                    if (0..26).contains(&k) {
                        cal.sum[k as usize] = (sx as f32, sy as f32);
                        cal.n[k as usize] = n;
                    }
                }
            }
        }
        cal
    }

    pub fn save_calibration(&self, cal: &Calibration) {
        let Some(conn) = &self.conn else { return };
        let run = || -> rusqlite::Result<()> {
            conn.execute_batch("BEGIN;")?;
            {
                let mut st = conn.prepare("INSERT OR REPLACE INTO key_calibration (key, sx, sy, n) VALUES (?1, ?2, ?3, ?4)")?;
                for k in 0..26 {
                    if cal.n[k] > 0 {
                        st.execute(params![k as i64, cal.sum[k].0 as f64, cal.sum[k].1 as f64, cal.n[k]])?;
                    }
                }
            }
            conn.execute_batch("COMMIT;")
        };
        if run().is_err() {
            let _ = conn.execute_batch("ROLLBACK;");
        }
    }

    pub fn clear_calibration(&self) {
        if let Some(conn) = &self.conn {
            let _ = conn.execute_batch("DELETE FROM key_calibration;");
        }
    }

    // ------------------------------------------------------------------ zaznam tahu

    /// Prida tah do zaznamu a orizne zaznam na poslednich `SWIPE_LOG_MAX` radku.
    pub fn swipe_log_add(&self, ts: i64, word: &str, first: &str, outcome: i64, geom: &str, points: &str) {
        let Some(conn) = &self.conn else { return };
        let _ = conn.execute(
            "INSERT INTO swipe_log (ts, word, first, outcome, geom, points) VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
            params![ts, word, first, outcome, geom, points],
        );
        let _ = conn.execute(
            "DELETE FROM swipe_log WHERE id NOT IN (SELECT id FROM swipe_log ORDER BY id DESC LIMIT ?1)",
            params![SWIPE_LOG_MAX],
        );
    }

    pub fn swipe_log_count(&self) -> i64 {
        let Some(conn) = &self.conn else { return 0 };
        conn.query_row("SELECT COUNT(*) FROM swipe_log", [], |r| r.get(0)).unwrap_or(0)
    }

    pub fn swipe_log_clear(&self) {
        if let Some(conn) = &self.conn {
            let _ = conn.execute_batch("DELETE FROM swipe_log;");
        }
    }

    /// Zaznam od nejstarsiho; radky s necitelnou geometrii se preskoci.
    pub fn swipe_log_list(&self) -> Vec<SwipeTrace> {
        let Some(conn) = &self.conn else { return Vec::new() };
        let Ok(mut st) = conn.prepare("SELECT ts, word, first, outcome, geom, points FROM swipe_log ORDER BY id") else {
            return Vec::new();
        };
        let rows = st.query_map([], |r| {
            Ok((
                r.get::<_, i64>(0)?,
                r.get::<_, String>(1)?,
                r.get::<_, String>(2)?,
                r.get::<_, i64>(3)?,
                r.get::<_, String>(4)?,
                r.get::<_, String>(5)?,
            ))
        });
        let out = match rows {
            Ok(rows) => rows
                .flatten()
                .filter_map(|(ts, word, first, outcome, geom, points)| {
                    let geom: KeyGeom = serde_json::from_str(&geom).ok()?;
                    let points = points.split(',').map(|p| p.trim().parse::<f32>()).collect::<Result<Vec<f32>, _>>().ok()?;
                    Some(SwipeTrace { ts, word, first, outcome, geom, points })
                })
                .collect(),
            Err(_) => Vec::new(),
        };
        out
    }

    // ------------------------------------------------------------------ emoji

    pub fn emoji_used(&self, emoji: &str, now: i64) {
        if let Some(conn) = &self.conn {
            let _ = conn.execute(
                "INSERT INTO emoji_recent (emoji, last_used) VALUES (?1, ?2)
                 ON CONFLICT(emoji) DO UPDATE SET last_used = ?2",
                params![emoji, now],
            );
            let _ = conn.execute(
                "DELETE FROM emoji_recent WHERE emoji NOT IN (
                     SELECT emoji FROM emoji_recent ORDER BY last_used DESC LIMIT ?1)",
                params![EMOJI_RECENT_MAX as i64],
            );
        }
    }

    pub fn emoji_recent(&self) -> Vec<String> {
        let Some(conn) = &self.conn else { return Vec::new() };
        let Ok(mut st) = conn.prepare("SELECT emoji FROM emoji_recent ORDER BY last_used DESC LIMIT ?1") else {
            return Vec::new();
        };
        let out = match st.query_map(params![EMOJI_RECENT_MAX as i64], |r| r.get::<_, String>(0)) {
            Ok(rows) => rows.flatten().collect(),
            Err(_) => Vec::new(),
        };
        out
    }
}

/// Export osobniho slovniku jako JSON text.
pub fn export_json(user: &UserDict) -> String {
    serde_json::to_string_pretty(&user.export()).unwrap_or_else(|_| "{}".to_string())
}

/// Nacte export z JSON; `None` pri neplatnem vstupu.
pub fn parse_export(json: &str) -> Option<Export> {
    serde_json::from_str(json).ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    const NOW: i64 = 1_800_000_000_000;

    fn temp_dir(tag: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!("rb_storage_{}_{}", tag, std::process::id()));
        let _ = fs::remove_dir_all(&d);
        d
    }

    #[test]
    fn settings_created_with_defaults_then_persisted() {
        let dir = temp_dir("settings");
        let st = Storage::open(&dir);
        let (s, existed) = st.load_settings();
        assert!(!existed);
        assert_eq!(s, Settings::default());
        let mut changed = s.clone();
        changed.theme = "light".into();
        changed.vibration_ms = 25;
        st.save_settings(&changed).unwrap();
        let (again, existed) = Storage::open(&dir).load_settings();
        assert!(existed);
        assert_eq!(again, changed);
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn corrupt_settings_fall_back_to_defaults() {
        let dir = temp_dir("corrupt");
        let st = Storage::open(&dir);
        fs::write(dir.join(SETTINGS_FILE), "{{{").unwrap();
        let (s, existed) = st.load_settings();
        assert!(!existed);
        assert_eq!(s, Settings::default());
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn user_words_survive_reopen() {
        let dir = temp_dir("user");
        {
            let st = Storage::open(&dir);
            st.put_word("ahoj", 3, NOW);
            st.put_word("ahoj", 4, NOW + 1);
            st.put_bigram("dobrý", "den", 2);
        }
        let st = Storage::open(&dir);
        let user = st.load_user();
        assert_eq!(user.get("ahoj").unwrap().count, 4);
        assert_eq!(user.next_words("dobrý")[0], ("den".to_string(), 2));
        st.delete_word("ahoj");
        assert!(st.load_user().get("ahoj").is_none());
        drop(st);
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn clipboard_keeps_30_newest() {
        let st = Storage::in_memory();
        for i in 0..40 {
            assert!(st.clip_add(&format!("položka číslo {i}"), NOW + i));
        }
        let list = st.clip_list();
        assert_eq!(list.len(), 30);
        assert_eq!(list[0].text, "položka číslo 39");
        assert_eq!(list[29].text, "položka číslo 10");
    }

    #[test]
    fn clipboard_rejects_passwords_and_dedups() {
        let st = Storage::in_memory();
        assert!(!st.clip_add("Tr0ub4dor&3", NOW));
        assert!(st.clip_add("stejný text", NOW));
        assert!(st.clip_add("jiný text", NOW + 1));
        assert!(st.clip_add("stejný text", NOW + 2));
        let list = st.clip_list();
        assert_eq!(list.len(), 2);
        assert_eq!(list[0].text, "stejný text");
    }

    #[test]
    fn clipboard_expiry_spares_pinned() {
        let st = Storage::in_memory();
        st.clip_add("stará připnutá", NOW);
        st.clip_add("stará", NOW + 1);
        st.clip_add("nová", NOW + 61 * 60_000);
        let pinned_id = st.clip_list().iter().find(|c| c.text == "stará připnutá").unwrap().id;
        st.clip_pin(pinned_id, true);
        st.clip_expire(NOW + 62 * 60_000, 60);
        let texts: Vec<String> = st.clip_list().into_iter().map(|c| c.text).collect();
        assert_eq!(texts, vec!["stará připnutá", "nová"]);
        st.clip_clear();
        assert!(st.clip_list().is_empty());
    }

    #[test]
    fn macros_crud() {
        let st = Storage::in_memory();
        assert!(st.macro_list().is_empty());
        assert!(st.macro_save(&Macro { id: 0, name: "Pozdrav".into(), text: "S pozdravem".into(), shortcut: "mfg".into() }));
        assert!(st.macro_save(&Macro { id: 0, name: "Mail".into(), text: "a@b.cz".into(), shortcut: "@@".into() }));
        let mut list = st.macro_list();
        assert_eq!(list.len(), 2);
        list[0].text = "S přátelským pozdravem".into();
        assert!(st.macro_save(&list[0]));
        assert_eq!(st.macro_list()[0].text, "S přátelským pozdravem");
        st.macro_delete(list[0].id);
        assert_eq!(st.macro_list().len(), 1);
        assert_eq!(st.macro_list()[0].shortcut, "@@");
    }

    #[test]
    fn voice_commands_crud() {
        let st = Storage::in_memory();
        let cmd = |phrase: &str, action: &str, text: &str| VoiceCommand {
            id: 0,
            phrase: phrase.into(),
            action: action.into(),
            text: text.into(),
        };
        assert!(st.voice_list().is_empty());
        assert!(st.voice_save(&cmd("smazat", "delete_word", "")));
        assert!(st.voice_save(&cmd("tecka", "text", ".")));
        let mut list = st.voice_list();
        assert_eq!(list.len(), 2);
        list[1].text = "!".into();
        assert!(st.voice_save(&list[1]));
        assert_eq!(st.voice_list()[1].text, "!");
        st.voice_delete(list[0].id);
        assert_eq!(st.voice_list().len(), 1);
        assert_eq!(st.voice_list()[0].phrase, "tecka");
        // limit poctu
        for i in 0..crate::voice::MAX_COMMANDS {
            let _ = st.voice_save(&cmd(&format!("p{i}"), "enter", ""));
        }
        assert_eq!(st.voice_list().len(), crate::voice::MAX_COMMANDS);
        assert!(!st.voice_save(&cmd("navic", "enter", "")));
    }

    #[test]
    fn trigrams_corrections_and_calibration_persist() {
        let dir = temp_dir("phase3");
        {
            let st = Storage::open(&dir);
            st.put_trigram("jak se", "máš", 3);
            st.put_correction("mas", "máš", 2);
            st.put_correction("blbost", "", 1);
            let mut cal = Calibration::default();
            for _ in 0..10 {
                cal.add(0, 0.2, -0.1);
            }
            st.save_calibration(&cal);
        }
        let st = Storage::open(&dir);
        let user = st.load_user();
        assert_eq!(user.next_after("jak", "se")[0], ("máš".to_string(), 3));
        assert_eq!(user.corrections_from("mas").collect::<Vec<_>>(), vec![("máš", 2)]);
        assert_eq!(user.rejections("blbost"), 1);
        let cal = st.load_calibration();
        assert_eq!(cal.n[0], 10);
        assert!((cal.offset(0).0 - 0.2).abs() < 1e-4);
        st.clear_calibration();
        assert_eq!(st.load_calibration().n[0], 0);
        drop(st);
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn swipe_log_keeps_newest_1000() {
        let st = Storage::in_memory();
        let geom = crate::layout::Layout::build(1440.0, 1000.0, true, crate::layout::MODE_LETTERS, true, "x").geom();
        let g = serde_json::to_string(&geom).unwrap();
        for i in 0..1005 {
            st.swipe_log_add(i, &format!("slovo{i}"), "prvni", (i % 3) as i64, &g, "1.5,2.5,0,3,4,16");
        }
        assert_eq!(st.swipe_log_count(), SWIPE_LOG_MAX);
        let list = st.swipe_log_list();
        assert_eq!(list.len(), 1000);
        assert_eq!(list[0].word, "slovo5");
        assert_eq!(list[999].word, "slovo1004");
        assert_eq!(list[0].points, vec![1.5, 2.5, 0.0, 3.0, 4.0, 16.0]);
        assert_eq!(list[0].geom.centers, geom.centers);
        st.swipe_log_clear();
        assert_eq!(st.swipe_log_count(), 0);
    }

    #[test]
    fn emoji_recents_ordered_by_last_use() {
        let st = Storage::in_memory();
        st.emoji_used("😀", NOW);
        st.emoji_used("🐶", NOW + 1);
        st.emoji_used("😀", NOW + 2);
        assert_eq!(st.emoji_recent(), vec!["😀", "🐶"]);
    }

    #[test]
    fn export_import_through_storage() {
        let st = Storage::in_memory();
        let mut user = UserDict::new();
        user.learn("Novák", "pan", NOW);
        let json = export_json(&user);
        let data = parse_export(&json).unwrap();
        st.replace_user(&data);
        let loaded = st.load_user();
        assert_eq!(loaded.get("Novák").unwrap().count, 1);
        assert!(parse_export("nonsense").is_none());
    }
}
