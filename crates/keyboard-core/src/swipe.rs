//! Swipe decoder: cisteni vzorku -> prevzorkovani na 64 bodu -> sekvence nejblizsich klaves
//! -> beam search (sirka 50) pres trie slovniku -> skore -> top 3.
//!
//! Model: slovo je lomena cara pres stredy svych klaves. Kazdy z 64 bodu drahy je prirazen
//! k useku (predchozi pismeno -> dalsi pismeno) a plati ctverec vzdalenosti od tohoto useku.
//! Kazde pismeno navic plati "kotvu": ctverec vzdalenosti bodu, ve kterem ho draha dosahla,
//! od stredu klavesy (draha musi kazde pismeno opravdu navstivit).
//! Zdvojena pismena jsou prechody bez pohybu. Hleda se v trie bez diakritiky (prst jede pres
//! zakladni pismena); skutecna slova vcetne variant s diakritikou (s -> s i š) se dohledaji
//! az pro nalezene kandidaty.
//!
//! Hot loop nealokuje: stavy jsou `Copy`, buffery se drzi v `SwipeDecoder` a znovu pouzivaji.

use crate::charmap::{self, fold_byte};
use crate::dictionary::Dictionary;
use crate::layout::KeyGeom;
use crate::learning::UserDict;
use fst::raw::Fst;

pub const RESAMPLE_N: usize = 64;
pub const BEAM_WIDTH: usize = 50;
pub const MIN_WORD_LEN: usize = 2;
pub const MAX_WORD_LEN: usize = 30;
pub const TOP_N: usize = 3;

/// Vahy skore dle zadani.
pub const W_GEOMETRY: f32 = 0.6;
pub const W_LANGUAGE: f32 = 0.25;
pub const W_PERSONAL: f32 = 0.15;

const NO_KEY: u8 = 255;
const STATE_CAP: usize = 8192;
const PRESORT_KEEP: usize = 8 * BEAM_WIDTH;
/// Maximalni vzdalenost bodu od stredu klavesy (v sirkach klaves), aby slo pismeno "navstivit".
const ANCHOR_RADIUS: f32 = 1.35;
const ANCHOR_WEIGHT: f32 = 1.0;
/// Mala penalizace za zdvojene pismeno (prechod bez pohybu).
const EPS_COST: f32 = 0.02;
/// Strmost prevodu prumerne chyby na geometricke skore 0..1.
const GEO_K: f32 = 3.0;
/// Jazykove skore pro osobni slova, ktera nejsou ve vestavenem slovniku.
const USER_ONLY_LM: f32 = 0.3;
/// Kratsi draha nez tohle (v sirkach klaves) je tap.
const MIN_PATH_UNITS: f32 = 0.7;
/// Pomalejsi tah nez tohle (sirky klaves za sekundu) je tap / drzeni.
const MIN_SPEED_UNITS_PER_S: f32 = 1.0;

type Pt = (f32, f32);

/// Penalizace skore za kazde smazani slova hned po swipe (nejvyse 5x).
const REJECT_PENALTY: f32 = 0.04;
/// Posun skore za kazdou zaznamenanou opravu "spatne -> spravne" (nejvyse 4x).
const CORRECTION_SHIFT: f32 = 0.02;

/// Minimalni pocet vzorku, nez se posun stredu klavesy zacne pouzivat.
const CAL_MIN_SAMPLES: u32 = 6;
/// Nejvetsi povoleny posun stredu klavesy (v sirkach klaves).
const CAL_MAX_OFFSET: f32 = 0.35;
/// Klouzave okno: starsi vzorky postupne ztraceji vahu.
const CAL_WINDOW: u32 = 150;
/// Zarovnani horsi nez tohle (prumerna chyba na bod) se k uceni nepouzije.
const CAL_MAX_MEAN_COST: f32 = 0.12;

/// Prizpusobeni swipe stylu psani: pro kazde pismeno prumerny rozdil mezi mistem,
/// kudy prst pri potvrzenych tazich skutecne jezdi, a stredem klavesy.
#[derive(Debug, Clone, Copy)]
pub struct Calibration {
    pub sum: [(f32, f32); 26],
    pub n: [u32; 26],
}

impl Default for Calibration {
    fn default() -> Self {
        Calibration { sum: [(0.0, 0.0); 26], n: [0; 26] }
    }
}

impl Calibration {
    pub fn add(&mut self, key: usize, dx: f32, dy: f32) {
        if key >= 26 || !dx.is_finite() || !dy.is_finite() {
            return;
        }
        if self.n[key] >= CAL_WINDOW {
            let keep = (CAL_WINDOW - 1) as f32 / CAL_WINDOW as f32;
            self.sum[key].0 *= keep;
            self.sum[key].1 *= keep;
            self.n[key] = CAL_WINDOW - 1;
        }
        self.sum[key].0 += dx;
        self.sum[key].1 += dy;
        self.n[key] += 1;
    }

    /// Posun stredu klavesy v normalizovanych jednotkach (0, dokud neni dost vzorku).
    pub fn offset(&self, key: usize) -> Pt {
        if key >= 26 || self.n[key] < CAL_MIN_SAMPLES {
            return (0.0, 0.0);
        }
        let n = self.n[key] as f32;
        (
            (self.sum[key].0 / n).clamp(-CAL_MAX_OFFSET, CAL_MAX_OFFSET),
            (self.sum[key].1 / n).clamp(-CAL_MAX_OFFSET, CAL_MAX_OFFSET),
        )
    }

    /// Geometrie s posunutymi stredy klaves.
    pub fn apply(&self, geom: &KeyGeom) -> KeyGeom {
        let mut g = *geom;
        for k in 0..26 {
            let o = self.offset(k);
            g.centers[k].0 += o.0;
            g.centers[k].1 += o.1;
        }
        g
    }

    /// Nauci se z potvrzeneho tahu: `word` je slovo bez diakritiky, `pts` jeho draha,
    /// `geom` neposunuta geometrie. Vraci true, kdyz se tah pouzil.
    pub fn learn(&mut self, word: &[u8], pts: &[Pt; RESAMPLE_N], geom: &KeyGeom, sensitivity: f32) -> bool {
        // Zarovnava se vuci aktualne posunutym stredum (tak, jak tah videl decoder),
        // posun se ale meri vuci skutecnym stredum klaves.
        let Some((anchors, mean)) = anchor_points(word, pts, &self.apply(geom), sensitivity) else { return false };
        if mean > CAL_MAX_MEAN_COST {
            return false;
        }
        for (k, p) in anchors {
            let c = geom.centers[k as usize];
            self.add(k as usize, p.0 - c.0, p.1 - c.1);
        }
        true
    }
}

#[derive(Clone, Copy)]
struct State {
    /// Cena vsech bodu az po kotvu (vcetne).
    cost: f32,
    /// Poradi v beamu: zaporny odhad nejlepsiho dosazitelneho skore (mensi = lepsi).
    rank: f32,
    /// Soucet vystupu FST po ceste = 255 - nejvyssi frekvence slova pod touto predponou.
    out: u32,
    addr: usize,
    hash: u64,
    word: [u8; 32],
    len: u8,
    key: u8,
    /// Index bodu drahy, ve kterem bylo dosazeno posledni pismeno.
    anchor: u8,
}

#[derive(Clone, Copy)]
struct Final {
    word: [u8; 32],
    len: u8,
    cost: f32,
    dict: u8,
}

#[derive(Debug, Clone, PartialEq)]
pub struct SwipeCandidate {
    pub word: String,
    pub score: f32,
    /// Prumerna geometricka chyba na bod (0 = dokonala shoda).
    pub cost: f32,
}

pub struct SwipeDecoder {
    cur: Vec<State>,
    next: Vec<State>,
    clean: Vec<Pt>,
    pts: [Pt; RESAMPLE_N],
    keyseq: Vec<u8>,
    finals: Vec<Final>,
}

impl Default for SwipeDecoder {
    fn default() -> Self {
        Self::new()
    }
}

#[inline]
fn dist2(a: Pt, b: Pt) -> f32 {
    let (dx, dy) = (a.0 - b.0, a.1 - b.1);
    dx * dx + dy * dy
}

/// Ctverec vzdalenosti bodu `p` od usecky `a`-`b`.
#[inline]
pub fn seg_dist2(p: Pt, a: Pt, b: Pt) -> f32 {
    let (vx, vy) = (b.0 - a.0, b.1 - a.1);
    let len2 = vx * vx + vy * vy;
    if len2 < 1e-9 {
        return dist2(p, a);
    }
    let t = (((p.0 - a.0) * vx + (p.1 - a.1) * vy) / len2).clamp(0.0, 1.0);
    dist2(p, (a.0 + t * vx, a.1 + t * vy))
}

#[inline]
fn key_of(b: u8, geom: &KeyGeom) -> u8 {
    let f = fold_byte(b);
    if f.is_ascii_lowercase() {
        let i = f - b'a';
        if geom.present[i as usize] {
            return i;
        }
    }
    NO_KEY
}

#[inline]
fn fnv(h: u64, b: u8) -> u64 {
    (h ^ b as u64).wrapping_mul(0x0000_0100_0000_01B3)
}
const FNV_INIT: u64 = 0xCBF2_9CE4_8422_2325;

/// Prevede surove vzorky (x, y, t v pixelech / ms) na normalizovane body a odstrani duplicity.
/// Vraci dobu tahu v ms.
pub fn clean_points(raw: &[f32], geom: &KeyGeom, out: &mut Vec<Pt>) -> f32 {
    out.clear();
    if geom.unit_w <= 0.0 || geom.row_h <= 0.0 {
        return 0.0;
    }
    let (mut t0, mut t1) = (f32::NAN, 0.0f32);
    for s in raw.chunks_exact(3) {
        if !(s[0].is_finite() && s[1].is_finite()) {
            continue;
        }
        let p = (s[0] / geom.unit_w, s[1] / geom.row_h);
        if s[2].is_finite() {
            if t0.is_nan() {
                t0 = s[2];
            }
            t1 = s[2];
        }
        match out.last() {
            Some(&last) if dist2(last, p) < 1e-4 => {}
            _ => out.push(p),
        }
    }
    if t0.is_nan() {
        0.0
    } else {
        (t1 - t0).max(0.0)
    }
}

pub fn path_length(pts: &[Pt]) -> f32 {
    pts.windows(2).map(|w| dist2(w[0], w[1]).sqrt()).sum()
}

/// Prevzorkuje drahu na presne `RESAMPLE_N` bodu rovnomerne podle delky drahy.
/// Vraci false pro prazdnou nebo nulovou drahu.
pub fn resample(pts: &[Pt], out: &mut [Pt; RESAMPLE_N]) -> bool {
    if pts.len() < 2 {
        return false;
    }
    let total = path_length(pts);
    if !(total > 1e-6) {
        return false;
    }
    let step = total / (RESAMPLE_N - 1) as f32;
    out[0] = pts[0];
    let mut n = 1;
    let mut carried = 0.0f32;
    let mut prev = pts[0];
    for &p in &pts[1..] {
        let mut seg = dist2(prev, p).sqrt();
        while n < RESAMPLE_N - 1 && carried + seg >= step && seg > 0.0 {
            let t = (step - carried) / seg;
            prev = (prev.0 + (p.0 - prev.0) * t, prev.1 + (p.1 - prev.1) * t);
            out[n] = prev;
            n += 1;
            seg = dist2(prev, p).sqrt();
            carried = 0.0;
        }
        carried += seg;
        prev = p;
    }
    let last = pts[pts.len() - 1];
    while n < RESAMPLE_N {
        out[n] = last;
        n += 1;
    }
    true
}

/// Pro kazdy bod najde nejblizsi klavesu; po sobe jdouci opakovani slouci.
/// Vysledek jsou indexy pismen 0..26 (a-z).
pub fn key_sequence(pts: &[Pt], geom: &KeyGeom, out: &mut Vec<u8>) {
    out.clear();
    for &p in pts {
        let mut best = NO_KEY;
        let mut best_d = f32::MAX;
        for i in 0..26 {
            if geom.present[i] {
                let d = dist2(p, geom.centers[i]);
                if d < best_d {
                    best_d = d;
                    best = i as u8;
                }
            }
        }
        if best != NO_KEY && out.last() != Some(&best) {
            out.push(best);
        }
    }
}

/// Cena zarovnani jednoho slova (sekvence klaves) na 64 bodu - stejny model jako beam search,
/// ale presne (dynamicke programovani). Pouziva se pro osobni slova mimo slovnik a v testech.
pub fn align_cost(word: &[u8], pts: &[Pt; RESAMPLE_N], geom: &KeyGeom, sensitivity: f32) -> Option<f32> {
    let inv = 1.0 / (sensitivity * sensitivity);
    let r2 = (ANCHOR_RADIUS * sensitivity) * (ANCHOR_RADIUS * sensitivity);
    let mut keys = [0u8; 32];
    let mut m = 0usize;
    let mut extra = 0.0f32;
    for &b in word.iter().take(MAX_WORD_LEN) {
        let k = key_of(b, geom);
        if k == NO_KEY || (m > 0 && keys[m - 1] == k) {
            if m == 0 {
                return None;
            }
            extra += EPS_COST;
            continue;
        }
        keys[m] = k;
        m += 1;
    }
    if m == 0 {
        return None;
    }
    let c = |j: usize| geom.centers[keys[j] as usize];
    let mut dp = [f32::INFINITY; 32];
    let d0 = dist2(pts[0], c(0));
    if d0 > r2 {
        return None;
    }
    dp[0] = ANCHOR_WEIGHT * d0 * inv;
    for i in 0..RESAMPLE_N - 1 {
        let (p, pn) = (pts[i], pts[i + 1]);
        for j in (0..m).rev() {
            let from = if j == 0 { c(0) } else { c(j - 1) };
            let seg = seg_dist2(pn, from, c(j)) * inv;
            let mut best = dp[j] + seg;
            if j > 0 && dp[j - 1].is_finite() {
                let anchor = dist2(p, c(j - 1));
                if anchor <= r2 {
                    best = best.min(dp[j - 1] + ANCHOR_WEIGHT * anchor * inv + seg);
                }
            }
            dp[j] = best;
        }
    }
    let total = dp[m - 1] + ANCHOR_WEIGHT * dist2(pts[RESAMPLE_N - 1], c(m - 1)) * inv + extra;
    total.is_finite().then_some(total)
}

/// Presne zarovnani slova na drahu vcetne bodu, ve kterych draha jednotliva pismena dosahla.
/// Vraci (klavesa, bod) pro kazde pismeno a prumernou cenu na bod.
pub fn anchor_points(word: &[u8], pts: &[Pt; RESAMPLE_N], geom: &KeyGeom, sensitivity: f32) -> Option<(Vec<(u8, Pt)>, f32)> {
    let sens = if sensitivity.is_finite() { sensitivity.clamp(0.5, 1.5) } else { 1.0 };
    let inv = 1.0 / (sens * sens);
    let r2 = (ANCHOR_RADIUS * sens) * (ANCHOR_RADIUS * sens);
    let mut keys: Vec<u8> = Vec::with_capacity(word.len());
    for &b in word.iter().take(MAX_WORD_LEN) {
        let k = key_of(b, geom);
        if k == NO_KEY {
            if keys.is_empty() {
                return None;
            }
            continue;
        }
        if keys.last() != Some(&k) {
            keys.push(k);
        }
    }
    let m = keys.len();
    if m < 2 {
        return None;
    }
    let c = |j: usize| geom.centers[keys[j] as usize];
    let last = RESAMPLE_N - 1;
    // cost[j][i] = nejlepsi cena, kdyz pismeno j bylo dosazeno v bode i.
    let mut cost = vec![[f32::INFINITY; RESAMPLE_N]; m];
    let mut back = vec![[0u8; RESAMPLE_N]; m];
    let d0 = dist2(pts[0], c(0));
    if d0 > r2 {
        return None;
    }
    cost[0][0] = ANCHOR_WEIGHT * d0 * inv;
    let mut pre = [0.0f32; RESAMPLE_N];
    for j in 1..m {
        for q in 1..RESAMPLE_N {
            pre[q] = pre[q - 1] + seg_dist2(pts[q], c(j - 1), c(j));
        }
        for i in 1..RESAMPLE_N {
            let a = dist2(pts[i], c(j));
            if a > r2 {
                continue;
            }
            let mut best = f32::INFINITY;
            let mut from = 0u8;
            for ip in 0..i {
                let prev = cost[j - 1][ip];
                if prev.is_finite() {
                    let v = prev + (pre[i] - pre[ip]) * inv;
                    if v < best {
                        best = v;
                        from = ip as u8;
                    }
                }
            }
            if best.is_finite() {
                cost[j][i] = best + ANCHOR_WEIGHT * a * inv;
                back[j][i] = from;
            }
        }
    }
    let total = cost[m - 1][last];
    if !total.is_finite() {
        return None;
    }
    let mut anchors = vec![(0u8, (0.0f32, 0.0f32)); m];
    let mut i = last;
    for j in (0..m).rev() {
        anchors[j] = (keys[j], pts[i]);
        i = back[j][i] as usize;
    }
    Some((anchors, total / RESAMPLE_N as f32))
}

/// Uceni z oprav: slova, ktera uzivatel po swipe mazal nebo nahrazoval jinou alternativou,
/// klesnou; slova, ktera misto nich vybiral, stoupnou (jen kdyz jsou obe mezi kandidaty).
fn apply_corrections(scored: &mut [SwipeCandidate], user: &UserDict) {
    let mut shifts: Vec<(usize, f32)> = Vec::new();
    for i in 0..scored.len() {
        let rejected = user.rejections(&scored[i].word).min(5);
        if rejected > 0 {
            shifts.push((i, -REJECT_PENALTY * rejected as f32));
        }
        for (good, count) in user.corrections_from(&scored[i].word) {
            if let Some(j) = scored.iter().position(|s| s.word == good) {
                let d = CORRECTION_SHIFT * count.min(4) as f32;
                shifts.push((i, -d));
                shifts.push((j, d));
            }
        }
    }
    for (i, d) in shifts {
        scored[i].score += d;
    }
}

/// Idealni draha slova pres stredy klaves jako surove vzorky (x, y, t) v pixelech.
/// Slouzi testum a benchmarkum (simulace tahu prstem).
pub fn ideal_path(word: &str, geom: &KeyGeom, samples_per_segment: usize) -> Vec<f32> {
    let mut centers: Vec<Pt> = Vec::new();
    if let Some(enc) = charmap::encode_word(word) {
        for b in enc {
            let k = key_of(b, geom);
            if k != NO_KEY {
                let c = geom.centers[k as usize];
                centers.push((c.0 * geom.unit_w, c.1 * geom.row_h));
            }
        }
    }
    let mut out = Vec::new();
    let mut t = 0.0f32;
    let n = samples_per_segment.max(1);
    for w in centers.windows(2) {
        for s in 0..n {
            let f = s as f32 / n as f32;
            out.extend_from_slice(&[w[0].0 + (w[1].0 - w[0].0) * f, w[0].1 + (w[1].1 - w[0].1) * f, t]);
            t += 8.0;
        }
    }
    if let Some(last) = centers.last() {
        out.extend_from_slice(&[last.0, last.1, t]);
    }
    out
}

impl SwipeDecoder {
    pub fn new() -> SwipeDecoder {
        SwipeDecoder {
            cur: Vec::with_capacity(STATE_CAP),
            next: Vec::with_capacity(STATE_CAP),
            clean: Vec::with_capacity(512),
            pts: [(0.0, 0.0); RESAMPLE_N],
            keyseq: Vec::with_capacity(RESAMPLE_N),
            finals: Vec::with_capacity(4 * BEAM_WIDTH),
        }
    }

    /// Sekvence nejblizsich klaves z posledniho dekodovani (pismena a-z).
    pub fn last_key_sequence(&self) -> String {
        self.keyseq.iter().map(|&k| (b'a' + k) as char).collect()
    }

    pub fn last_resampled(&self) -> &[Pt; RESAMPLE_N] {
        &self.pts
    }

    /// Dekoduje tah. `raw` = trojice (x, y, t). Vraci nejvyse `TOP_N` navrhu serazenych podle skore;
    /// prazdny seznam znamena "neni to swipe" (prilis kratke / pomale / neplatna data) -> brat jako tap.
    pub fn decode(
        &mut self,
        raw: &[f32],
        geom: &KeyGeom,
        dicts: &[&Dictionary],
        user: &UserDict,
        sensitivity: f32,
        now: i64,
    ) -> Vec<SwipeCandidate> {
        let sens = if sensitivity.is_finite() { sensitivity.clamp(0.5, 1.5) } else { 1.0 };
        let duration_ms = clean_points(raw, geom, &mut self.clean);
        let length = path_length(&self.clean);
        if self.clean.len() < 2 || length < MIN_PATH_UNITS {
            return Vec::new();
        }
        if duration_ms > 0.0 && length / (duration_ms / 1000.0) < MIN_SPEED_UNITS_PER_S {
            return Vec::new();
        }
        if !resample(&self.clean, &mut self.pts) {
            return Vec::new();
        }
        key_sequence(&self.pts, geom, &mut self.keyseq);
        if self.keyseq.len() < MIN_WORD_LEN {
            return Vec::new();
        }

        self.finals.clear();
        for (di, dict) in dicts.iter().enumerate().take(255) {
            self.beam(dict.swipe_fst(), geom, sens, di as u8);
        }

        // Skore = 0.6 geometrie + 0.25 jazyk + 0.15 osobni frekvence.
        let mut scored: Vec<SwipeCandidate> = Vec::with_capacity(self.finals.len() + 4);
        for f in &self.finals {
            let Some(dict) = dicts.get(f.dict as usize) else { continue };
            let mean = f.cost / RESAMPLE_N as f32;
            let geo = W_GEOMETRY * (-GEO_K * mean).exp();
            // Trie pro swipe je bez diakritiky: ke kazdemu nalezu dohledame skutecna slova
            // (s -> s i š ...) vcetne frekvence.
            for v in crate::predict::accent_variants(dict, &f.word[..f.len as usize]) {
                let mut word = charmap::decode_word(&v.enc);
                if v.entry.cap {
                    word = charmap::capitalize(&word);
                }
                let score = geo + W_LANGUAGE * (v.entry.freq as f32 / 255.0) + W_PERSONAL * user.strength(&word, now);
                scored.push(SwipeCandidate { word, score, cost: mean });
            }
        }

        // Osobni slova, ktera vestaveny slovnik nezna.
        for (w, uw) in user.iter() {
            let Some(enc) = charmap::encode_word(w) else { continue };
            if enc.len() < MIN_WORD_LEN || dicts.iter().any(|d| d.lookup(&enc).is_some()) {
                continue;
            }
            if let Some(cost) = align_cost(&enc, &self.pts, geom, sens) {
                let mean = cost / RESAMPLE_N as f32;
                let score = W_GEOMETRY * (-GEO_K * mean).exp()
                    + W_LANGUAGE * USER_ONLY_LM
                    + W_PERSONAL * crate::learning::strength_of(*uw, now);
                scored.push(SwipeCandidate { word: w.clone(), score, cost: mean });
            }
        }

        apply_corrections(&mut scored, user);
        scored.sort_by(|a, b| b.score.total_cmp(&a.score));
        let mut out: Vec<SwipeCandidate> = Vec::with_capacity(TOP_N);
        for c in scored {
            if out.len() >= TOP_N {
                break;
            }
            if !out.iter().any(|o| o.word == c.word) {
                out.push(c);
            }
        }
        out
    }

    /// Beam search pres jeden slovnik; vysledky prida do `self.finals`.
    ///
    /// Stav = predpona, jejiz posledni pismeno uz draha navstivila (v bode `anchor`).
    /// Na dalsi pismeno se prechazi az ve chvili, kdy se k nemu draha priblizi: teprve tehdy
    /// se secte cena vsech bodu mezi obema pismeny vuci jejich spojnici. Diky tomu v beamu
    /// nejsou spekulativni stavy typu "mozna pojedu k X" a 50 mist staci i na dlouha slova.
    fn beam(&mut self, fst: &Fst<Vec<u8>>, geom: &KeyGeom, sens: f32, dict: u8) {
        let inv = 1.0 / (sens * sens);
        let r2 = (ANCHOR_RADIUS * sens) * (ANCHOR_RADIUS * sens);
        let pts = self.pts;
        let last = RESAMPLE_N - 1;

        self.cur.clear();
        let root = fst.root();
        for t in root.transitions() {
            let k = key_of(t.inp, geom);
            if k == NO_KEY {
                continue;
            }
            let d = dist2(pts[0], geom.centers[k as usize]);
            if d <= r2 {
                let mut word = [0u8; 32];
                word[0] = t.inp;
                let s = State {
                    cost: ANCHOR_WEIGHT * d * inv,
                    rank: 0.0,
                    out: t.out.value() as u32,
                    addr: t.addr,
                    hash: fnv(FNV_INIT, t.inp),
                    word,
                    len: 1,
                    key: k,
                    anchor: 0,
                };
                push(&mut self.cur, s);
                push_doubled(&mut self.cur, fst, geom, &s);
            }
        }
        prune(&mut self.cur, &pts, 0, geom, inv);

        for j in 1..RESAMPLE_N {
            if self.cur.is_empty() {
                return;
            }
            let pj = pts[j];
            self.next.clear();
            for si in 0..self.cur.len() {
                let s = self.cur[si];
                if j < last {
                    push(&mut self.next, s); // dal cekat na dalsi pismeno
                }
                if (s.len as usize) >= MAX_WORD_LEN {
                    continue;
                }
                let c_from = geom.centers[s.key as usize];
                let node = fst.node(s.addr);
                for t in node.transitions() {
                    let k = key_of(t.inp, geom);
                    if k == NO_KEY || k == s.key {
                        continue; // zdvojena pismena resi `push_doubled`
                    }
                    let c_to = geom.centers[k as usize];
                    let anchor = dist2(pj, c_to);
                    if anchor > r2 {
                        continue;
                    }
                    let mut transit = 0.0f32;
                    for p in &pts[s.anchor as usize + 1..=j] {
                        transit += seg_dist2(*p, c_from, c_to);
                    }
                    let mut child = extend(&s, t.inp, t.addr, t.out.value());
                    child.cost = s.cost + (transit + ANCHOR_WEIGHT * anchor) * inv;
                    child.key = k;
                    child.anchor = j as u8;
                    if j < last {
                        push(&mut self.next, child);
                        push_doubled(&mut self.next, fst, geom, &child);
                    } else {
                        self.finish(fst, geom, &child, dict);
                    }
                }
            }
            if j < last {
                prune(&mut self.next, &pts, j, geom, inv);
                std::mem::swap(&mut self.cur, &mut self.next);
            }
        }
    }

    /// Zapise hotove slovo (a pripadnou variantu se zdvojenym poslednim pismenem).
    fn finish(&mut self, fst: &Fst<Vec<u8>>, geom: &KeyGeom, s: &State, dict: u8) {
        let node = fst.node(s.addr);
        if node.is_final() && (s.len as usize) >= MIN_WORD_LEN && self.finals.len() < self.finals.capacity() {
            self.finals.push(Final { word: s.word, len: s.len, cost: s.cost, dict });
        }
        if (s.len as usize) < MAX_WORD_LEN {
            for t in node.transitions() {
                if key_of(t.inp, geom) == s.key && fst.node(t.addr).is_final() && self.finals.len() < self.finals.capacity() {
                    let d = extend(s, t.inp, t.addr, t.out.value());
                    self.finals.push(Final { word: d.word, len: d.len, cost: s.cost + EPS_COST, dict });
                }
            }
        }
    }
}

#[inline]
fn extend(s: &State, inp: u8, addr: usize, out: u64) -> State {
    let mut c = *s;
    c.out = s.out.saturating_add(out as u32);
    c.word[c.len as usize] = inp;
    c.len += 1;
    c.addr = addr;
    c.hash = fnv(s.hash, inp);
    c
}

/// Vlozeni bez realokace: buffer ma pevnou kapacitu, pretekle stavy se zahodi.
#[inline]
fn push(v: &mut Vec<State>, s: State) {
    if v.len() < v.capacity() && s.cost.is_finite() {
        v.push(s);
    }
}

/// Zdvojene pismeno ("nn" v "panna"): stejna klavesa, zadny pohyb -> stav se stejnou kotvou.
#[inline]
fn push_doubled(v: &mut Vec<State>, fst: &Fst<Vec<u8>>, geom: &KeyGeom, s: &State) {
    if (s.len as usize) >= MAX_WORD_LEN {
        return;
    }
    let node = fst.node(s.addr);
    for t in node.transitions() {
        if key_of(t.inp, geom) == s.key {
            let mut d = extend(s, t.inp, t.addr, t.out.value());
            d.cost += EPS_COST;
            push(v, d);
        }
    }
}

/// Ponecha `BEAM_WIDTH` nejlepsich stavu, kazdou predponu nejvyse jednou.
///
/// Poradi = odhad nejlepsiho skore, ktereho muze stav jeste dosahnout (stejny vzorec jako
/// vysledne skore): geometrie z dosavadni ceny + jazyk z nejcastejsiho slova pod predponou.
/// Body, ktere stav jeste nezapocital (ceka na dalsi pismeno), se odhadnou vuci primce od
/// posledniho pismene k aktualnimu bodu - kdyz draha mezitim zatocila, stav nejake pismeno minul.
fn prune(v: &mut Vec<State>, pts: &[Pt; RESAMPLE_N], j: usize, geom: &KeyGeom, inv: f32) {
    let here = pts[j];
    for s in v.iter_mut() {
        let from = geom.centers[s.key as usize];
        let mut pending = 0.0f32;
        for p in &pts[s.anchor as usize + 1..=j] {
            pending += seg_dist2(*p, from, here);
        }
        let mean = (s.cost + pending * inv) / RESAMPLE_N as f32;
        let best_freq = 255u32.saturating_sub(s.out) as f32 / 255.0;
        s.rank = -(W_GEOMETRY * (-GEO_K * mean).exp() + W_LANGUAGE * best_freq);
    }
    if v.len() > PRESORT_KEEP {
        v.select_nth_unstable_by(PRESORT_KEEP, |a, b| a.rank.total_cmp(&b.rank));
        v.truncate(PRESORT_KEEP);
    }
    v.sort_unstable_by(|a, b| a.rank.total_cmp(&b.rank).then(a.hash.cmp(&b.hash)));
    let mut kept = 0usize;
    for i in 0..v.len() {
        if kept >= BEAM_WIDTH {
            break;
        }
        let h = v[i].hash;
        let len = v[i].len;
        if v[..kept].iter().any(|k| k.hash == h && k.len == len) {
            continue;
        }
        v[kept] = v[i];
        kept += 1;
    }
    v.truncate(kept);
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::layout::{Layout, MODE_LETTERS};

    const NOW: i64 = 1_800_000_000_000;

    fn geom() -> KeyGeom {
        Layout::build(1440.0, 1000.0, true, MODE_LETTERS, true, "x").geom()
    }

    fn dict() -> Dictionary {
        Dictionary::from_words(
            "cs",
            &[
                ("ahoj", 150), ("ahold", 60), ("přítel", 150), ("pes", 150), ("pás", 120), ("panna", 110),
                ("pana", 140), ("den", 190), ("dne", 170), ("jak", 190), ("jako", 200), ("se", 220),
                ("máš", 150), ("mas", 90), ("mám", 170), ("být", 230), ("byt", 150), ("svět", 160),
                ("klávesnice", 120), ("děkuji", 150), ("prosím", 170), ("dobrý", 175), ("to", 230), ("ten", 220),
            ],
        )
    }

    fn top(words: &[SwipeCandidate]) -> Vec<&str> {
        words.iter().map(|c| c.word.as_str()).collect()
    }

    #[test]
    fn resample_gives_64_evenly_spaced_points() {
        let pts = [(0.0, 0.0), (1.0, 0.0), (1.0, 2.0), (5.0, 2.0)];
        let mut out = [(0.0, 0.0); RESAMPLE_N];
        assert!(resample(&pts, &mut out));
        assert_eq!(out[0], (0.0, 0.0));
        assert!(dist2(out[RESAMPLE_N - 1], (5.0, 2.0)) < 1e-6);
        let step = 7.0 / 63.0;
        for w in out.windows(2) {
            let d = dist2(w[0], w[1]).sqrt();
            // v rozich je tetiva kratsi nez oblouk, jinak presne krok
            assert!(d <= step + 1e-3 && d > step * 0.6, "{d} vs {step}");
        }
    }

    #[test]
    fn resample_rejects_degenerate_paths() {
        let mut out = [(0.0, 0.0); RESAMPLE_N];
        assert!(!resample(&[], &mut out));
        assert!(!resample(&[(1.0, 1.0)], &mut out));
        assert!(!resample(&[(1.0, 1.0), (1.0, 1.0)], &mut out));
    }

    #[test]
    fn clean_points_removes_duplicates_and_garbage() {
        let g = geom();
        let raw = [100.0, 100.0, 0.0, 100.0, 100.0, 8.0, f32::NAN, 5.0, 16.0, 400.0, 100.0, 24.0];
        let mut out = Vec::new();
        let dur = clean_points(&raw, &g, &mut out);
        assert_eq!(out.len(), 2);
        assert_eq!(dur, 24.0);
    }

    #[test]
    fn key_sequence_follows_path() {
        let g = geom();
        let raw = ideal_path("pes", &g, 20);
        let mut clean = Vec::new();
        clean_points(&raw, &g, &mut clean);
        let mut pts = [(0.0, 0.0); RESAMPLE_N];
        assert!(resample(&clean, &mut pts));
        let mut seq = Vec::new();
        key_sequence(&pts, &g, &mut seq);
        let s: String = seq.iter().map(|&k| (b'a' + k) as char).collect();
        assert!(s.starts_with('p') && s.ends_with('s') && s.contains('e'), "{s}");
    }

    #[test]
    fn segment_distance() {
        assert!((seg_dist2((0.5, 1.0), (0.0, 0.0), (1.0, 0.0)) - 1.0).abs() < 1e-6);
        assert!((seg_dist2((2.0, 0.0), (0.0, 0.0), (1.0, 0.0)) - 1.0).abs() < 1e-6);
        assert!((seg_dist2((3.0, 4.0), (0.0, 0.0), (0.0, 0.0)) - 25.0).abs() < 1e-5);
    }

    #[test]
    fn decodes_simple_word() {
        let (g, d) = (geom(), dict());
        let mut dec = SwipeDecoder::new();
        let res = dec.decode(&ideal_path("ahoj", &g, 12), &g, &[&d], &UserDict::new(), 1.0, NOW);
        assert_eq!(top(&res)[0], "ahoj");
        assert!(res.len() <= TOP_N);
        assert!(res[0].cost < 0.01);
    }

    #[test]
    fn decodes_words_with_diacritics_from_base_keys() {
        let (g, d) = (geom(), dict());
        let mut dec = SwipeDecoder::new();
        let user = UserDict::new();
        assert_eq!(top(&dec.decode(&ideal_path("pritel", &g, 12), &g, &[&d], &user, 1.0, NOW))[0], "přítel");
        assert_eq!(top(&dec.decode(&ideal_path("dekuji", &g, 12), &g, &[&d], &user, 1.0, NOW))[0], "děkuji");
        // "mas" i "máš" sdili drahu: obe varianty v navrzich, castejsi prvni
        let res = dec.decode(&ideal_path("mas", &g, 12), &g, &[&d], &user, 1.0, NOW);
        assert_eq!(top(&res)[0], "máš");
        assert!(top(&res).contains(&"mas"));
    }

    #[test]
    fn decodes_double_letters() {
        let (g, d) = (geom(), dict());
        let mut dec = SwipeDecoder::new();
        let res = dec.decode(&ideal_path("panna", &g, 12), &g, &[&d], &UserDict::new(), 1.0, NOW);
        let words = top(&res);
        assert!(words.contains(&"panna") && words.contains(&"pana"), "{words:?}");
    }

    #[test]
    fn decodes_long_word_with_noise() {
        let (g, d) = (geom(), dict());
        let mut dec = SwipeDecoder::new();
        let mut raw = ideal_path("klavesnice", &g, 10);
        for (i, s) in raw.chunks_exact_mut(3).enumerate() {
            // deterministicky "tres" +-25 % sirky klavesy
            s[0] += ((i * 37 % 11) as f32 / 10.0 - 0.5) * 0.5 * g.unit_w;
            s[1] += ((i * 53 % 7) as f32 / 6.0 - 0.5) * 0.5 * g.row_h;
        }
        let res = dec.decode(&raw, &g, &[&d], &UserDict::new(), 1.0, NOW);
        assert_eq!(top(&res)[0], "klávesnice");
    }

    #[test]
    fn short_or_slow_or_invalid_input_is_not_a_swipe() {
        let (g, d) = (geom(), dict());
        let mut dec = SwipeDecoder::new();
        let user = UserDict::new();
        // kratky pohyb v ramci jedne klavesy
        assert!(dec.decode(&[100.0, 100.0, 0.0, 110.0, 104.0, 30.0], &g, &[&d], &user, 1.0, NOW).is_empty());
        // prilis pomaly tah (10 s)
        let mut slow = ideal_path("ahoj", &g, 12);
        for (i, s) in slow.chunks_exact_mut(3).enumerate() {
            s[2] = i as f32 * 300.0;
        }
        assert!(dec.decode(&slow, &g, &[&d], &user, 1.0, NOW).is_empty());
        // neplatna data nesmi shodit decoder
        assert!(dec.decode(&[f32::NAN; 30], &g, &[&d], &user, 1.0, NOW).is_empty());
        assert!(dec.decode(&[], &g, &[&d], &user, 1.0, NOW).is_empty());
        assert!(dec.decode(&ideal_path("ahoj", &g, 12), &g, &[], &user, 1.0, NOW).is_empty());
    }

    #[test]
    fn beam_cost_matches_exact_alignment() {
        let (g, d) = (geom(), dict());
        let mut dec = SwipeDecoder::new();
        let res = dec.decode(&ideal_path("prosim", &g, 12), &g, &[&d], &UserDict::new(), 1.0, NOW);
        assert_eq!(res[0].word, "prosím");
        let enc = charmap::encode_word("prosím").unwrap();
        // Beam je aproximace presneho zarovnani: nikdy nemuze byt lepsi a musi byt blizko.
        let exact = align_cost(&enc, dec.last_resampled(), &g, 1.0).unwrap() / RESAMPLE_N as f32;
        assert!(res[0].cost >= exact - 1e-5 && res[0].cost - exact < 5e-3, "{exact} vs {}", res[0].cost);
    }

    #[test]
    fn score_weights_follow_spec() {
        assert!((W_GEOMETRY + W_LANGUAGE + W_PERSONAL - 1.0).abs() < 1e-6);
        assert_eq!((W_GEOMETRY, W_LANGUAGE, W_PERSONAL), (0.6, 0.25, 0.15));
        assert_eq!(BEAM_WIDTH, 50);
    }

    #[test]
    fn personal_frequency_changes_ranking() {
        let (g, d) = (geom(), dict());
        let mut dec = SwipeDecoder::new();
        let path = ideal_path("mas", &g, 12);
        let mut user = UserDict::new();
        assert_eq!(dec.decode(&path, &g, &[&d], &user, 1.0, NOW)[0].word, "máš");
        for _ in 0..40 {
            user.learn("mas", "", NOW);
        }
        assert_eq!(dec.decode(&path, &g, &[&d], &user, 1.0, NOW)[0].word, "mas");
    }

    #[test]
    fn personal_words_outside_dictionary_are_swipeable() {
        let (g, d) = (geom(), dict());
        let mut dec = SwipeDecoder::new();
        let mut user = UserDict::new();
        user.learn("Rustboard", "", NOW);
        let res = dec.decode(&ideal_path("rustboard", &g, 12), &g, &[&d], &user, 1.0, NOW);
        assert_eq!(res[0].word, "Rustboard");
    }

    #[test]
    fn frequent_word_wins_between_equal_paths() {
        let (g, d) = (geom(), dict());
        let mut dec = SwipeDecoder::new();
        // "byt" a "být" maji stejnou drahu; castejsi "být" je prvni
        let res = dec.decode(&ideal_path("byt", &g, 12), &g, &[&d], &UserDict::new(), 1.0, NOW);
        assert_eq!(top(&res)[..2], ["být", "byt"]);
    }

    #[test]
    fn corrections_demote_wrong_word_and_promote_chosen_one() {
        let (g, d) = (geom(), dict());
        let mut dec = SwipeDecoder::new();
        let path = ideal_path("mas", &g, 12);
        let mut user = UserDict::new();
        assert_eq!(dec.decode(&path, &g, &[&d], &user, 1.0, NOW)[0].word, "máš");
        // uzivatel dvakrat nahradil "máš" alternativou "mas"
        user.note_correction("máš", "mas");
        user.note_correction("máš", "mas");
        assert_eq!(dec.decode(&path, &g, &[&d], &user, 1.0, NOW)[0].word, "mas");
    }

    #[test]
    fn rejected_word_drops_in_ranking() {
        let (g, d) = (geom(), dict());
        let mut dec = SwipeDecoder::new();
        let path = ideal_path("byt", &g, 12);
        let mut user = UserDict::new();
        assert_eq!(dec.decode(&path, &g, &[&d], &user, 1.0, NOW)[0].word, "být");
        for _ in 0..3 {
            user.note_correction("být", ""); // smazano hned po swipe
        }
        assert_eq!(dec.decode(&path, &g, &[&d], &user, 1.0, NOW)[0].word, "byt");
        // normalni pouziti odmitnuti postupne odpousti
        for _ in 0..3 {
            user.forgive("být");
        }
        assert_eq!(dec.decode(&path, &g, &[&d], &user, 1.0, NOW)[0].word, "být");
    }

    #[test]
    fn anchor_points_sit_on_key_centers() {
        let g = geom();
        let mut dec = SwipeDecoder::new();
        let d = dict();
        dec.decode(&ideal_path("ahoj", &g, 12), &g, &[&d], &UserDict::new(), 1.0, NOW);
        let (anchors, mean) = anchor_points(b"ahoj", dec.last_resampled(), &g, 1.0).unwrap();
        assert_eq!(anchors.iter().map(|a| (b'a' + a.0) as char).collect::<String>(), "ahoj");
        assert!(mean < 0.01);
        for (k, p) in anchors {
            assert!(dist2(p, g.centers[k as usize]) < 0.05, "{k}");
        }
        assert!(anchor_points(b"pes", dec.last_resampled(), &g, 1.0).is_none());
    }

    #[test]
    fn calibration_learns_systematic_offset() {
        let (g, d) = (geom(), dict());
        let mut dec = SwipeDecoder::new();
        let mut cal = Calibration::default();
        // uzivatel jezdi soustavne o 0.25 sirky klavesy vpravo a 0.2 radku nize
        let shifted = |w: &str| {
            let mut p = ideal_path(w, &g, 12);
            for s in p.chunks_exact_mut(3) {
                s[0] += 0.25 * g.unit_w;
                s[1] += 0.2 * g.row_h;
            }
            p
        };
        assert_eq!(cal.offset(0), (0.0, 0.0));
        for _ in 0..8 {
            for w in ["ahoj", "jako", "jak"] {
                dec.decode(&shifted(w), &g, &[&d], &UserDict::new(), 1.0, NOW);
                assert!(cal.learn(w.as_bytes(), dec.last_resampled(), &g, 1.0));
            }
        }
        let a = cal.offset(0);
        assert!((a.0 - 0.25).abs() < 0.05 && (a.1 - 0.2).abs() < 0.05, "{a:?}");
        // s prizpusobenou geometrii posunuty tah sedi lepe nez bez ni
        let before = dec.decode(&shifted("ahoj"), &g, &[&d], &UserDict::new(), 1.0, NOW)[0].cost;
        let after = dec.decode(&shifted("ahoj"), &cal.apply(&g), &[&d], &UserDict::new(), 1.0, NOW)[0].cost;
        assert!(after < before * 0.5, "{after} vs {before}");
        // posun je omezeny
        for _ in 0..50 {
            cal.add(3, 5.0, -5.0);
        }
        assert_eq!(cal.offset(3), (0.35, -0.35));
    }

    #[test]
    fn two_languages_are_merged() {
        let g = geom();
        let cs = dict();
        let en = Dictionary::from_words("en", &[("hello", 150), ("help", 150), ("the", 240)]);
        let mut dec = SwipeDecoder::new();
        let res = dec.decode(&ideal_path("hello", &g, 12), &g, &[&cs, &en], &UserDict::new(), 1.0, NOW);
        assert_eq!(res[0].word, "hello");
    }
}
