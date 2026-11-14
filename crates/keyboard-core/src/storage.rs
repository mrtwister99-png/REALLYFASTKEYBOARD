//! Uloziste: `settings.json` (serde) + SQLite `rustboard.db` (osobni slovnik, bigramy,
//! schranka, nedavne emoji) + export/import osobniho slovniku jako JSON.
//!
//! SIFROVANI: databaze neni sifrovana na urovni aplikace. Duvod: SQLCipher vyzaduje
//! krizovou kompilaci OpenSSL pro Android a ChaCha20Poly1305 dalsi kryptografickou
//! zavislost mimo povoleny seznam (serde, serde_json, rusqlite, rayon, fst, once_cell).
//! Data lezi v privatnim adresari aplikace (/data/data/cz.rustboard/files), ktery je
//! chranen sandboxem Androidu a na S25 Ultra sifrovan systemovym file-based encryption
//! (klice v hardwarovem Keystore). Aplikace nema opravneni INTERNET a zalohy jsou vypnute
//! (allowBackup=false), takze data zarizeni neopusti a pri odinstalaci se smazou.

use crate::clipboard::{self, ClipItem};
use crate::learning::{Export, UserDict};
use crate::settings::Settings;
use rusqlite::{params, Connection};
use std::fs;
use std::io;
use std::path::{Path, PathBuf};

const SETTINGS_FILE: &str = "settings.json";
const DB_FILE: &str = "rustboard.db";
const EMOJI_RECENT_MAX: usize = 32;

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
";

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
        user
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
            let _ = conn.execute(
                "DELETE FROM user_bigrams WHERE w2 = ?1 OR w1 = ?2",
                params![word, word.to_lowercase()],
            );
        }
    }

    pub fn clear_user(&self) {
        if let Some(conn) = &self.conn {
            let _ = conn.execute_batch("DELETE FROM user_words; DELETE FROM user_bigrams;");
        }
    }

    /// Prepise cely osobni slovnik v jedne transakci (po importu).
    pub fn replace_user(&self, data: &Export) {
        let Some(conn) = &self.conn else { return };
        let run = || -> rusqlite::Result<()> {
            conn.execute_batch("BEGIN; DELETE FROM user_words; DELETE FROM user_bigrams;")?;
            {
                let mut st = conn.prepare("INSERT OR REPLACE INTO user_words (word, count, last_used) VALUES (?1, ?2, ?3)")?;
                for w in &data.words {
                    st.execute(params![w.word, w.count, w.last_used])?;
                }
                let mut st = conn.prepare("INSERT OR REPLACE INTO user_bigrams (w1, w2, count) VALUES (?1, ?2, ?3)")?;
                for b in &data.bigrams {
                    st.execute(params![b.w1, b.w2, b.count])?;
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
