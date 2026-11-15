//! Uplna zaloha do jednoho JSON souboru: nastaveni, osobni slovnik (slova, bigramy, trigramy),
//! opravy, makra a prizpusobeni swipe. Schranka, nedavne emoji a zaznam tahu se zamerne
//! nezalohuji (soukromi). Vsechna pole maji `#[serde(default)]`, takze starsi / castecne
//! soubory se nactou.

use crate::learning::Export;
use crate::macros::Macro;
use serde::{Deserialize, Serialize};

pub const APP_NAME: &str = "reallyFAST";
pub const BACKUP_VERSION: u32 = 1;

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct BackupCorrection {
    pub bad: String,
    pub good: String,
    pub count: u32,
}

/// Prizpusobeni jedne klavesy (index 0 = a ... 25 = z): soucet posunu a pocet vzorku.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct BackupKey {
    pub key: u32,
    pub sx: f32,
    pub sy: f32,
    pub n: u32,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default)]
pub struct Backup {
    pub app: String,
    pub version: u32,
    /// Cas vytvoreni (ms od epochy).
    pub created: i64,
    /// Nastaveni jako JSON objekt; chybi-li, zustane aktualni nastaveni.
    pub settings: Option<serde_json::Value>,
    pub user: Export,
    pub corrections: Vec<BackupCorrection>,
    pub macros: Vec<Macro>,
    pub calibration: Vec<BackupKey>,
}
