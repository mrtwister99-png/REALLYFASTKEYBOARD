//! Benchmarky jadra nad realnym slovnikem (app/src/main/assets/dictionary.bin).
//! Spusteni: `cargo bench` v crates/keyboard-core.
//!
//! Meri: nacteni slovniku, dictionary lookup, swipe decode (cs, cs+en), navrhy pri psani,
//! predikci dalsiho slova a presnost swipe na simulovanych tazich (idealni draha + sum).

use keyboard_core::charmap;
use keyboard_core::dictionary::{self, Dictionary};
use keyboard_core::layout::{Layout, MODE_LETTERS};
use keyboard_core::learning::UserDict;
use keyboard_core::predict::{self, Context};
use keyboard_core::swipe::{ideal_path, SwipeDecoder};
use std::hint::black_box;
use std::path::PathBuf;
use std::time::Instant;

const NOW: i64 = 1_800_000_000_000;

const CS_WORDS: &[&str] = &[
    "ahoj", "dobrý", "den", "děkuji", "prosím", "jak", "se", "máš", "dnes", "zítra", "večer", "ráno",
    "práce", "doma", "škola", "auto", "telefon", "klávesnice", "počítač", "zpráva", "napsat", "přijdu",
    "později", "možná", "určitě", "samozřejmě", "protože", "který", "také", "jsem", "budu", "můžeš",
    "chtěl", "vědět", "člověk", "život", "město", "Praha", "česky", "rychle", "pomalu", "dobře", "špatně",
    "nevím", "myslím", "rozumím", "potřebuji", "děkujeme", "víkend", "dovolená",
];
const EN_WORDS: &[&str] = &[
    "hello", "thanks", "please", "tomorrow", "today", "keyboard", "because", "people", "would", "there",
    "their", "about", "which", "message", "morning", "evening", "weekend", "question", "answer", "really",
];

/// Deterministicky generator (LCG) - benchmark je opakovatelny.
struct Lcg(u64);
impl Lcg {
    fn next(&mut self) -> f32 {
        self.0 = self.0.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
        ((self.0 >> 40) as f32 / (1u64 << 24) as f32) - 0.5
    }
}

fn noisy(path: &[f32], unit_w: f32, row_h: f32, amount: f32, rng: &mut Lcg) -> Vec<f32> {
    // Pomaly se menici posun (prst miji stredy klaves) + drobny tres.
    let mut out = path.to_vec();
    let (mut ox, mut oy) = (0.0f32, 0.0f32);
    for s in out.chunks_exact_mut(3) {
        ox = 0.9 * ox + 0.1 * rng.next() * 2.0 * amount * 3.0;
        oy = 0.9 * oy + 0.1 * rng.next() * 2.0 * amount * 3.0;
        s[0] += (ox + rng.next() * 0.1 * amount) * unit_w;
        s[1] += (oy + rng.next() * 0.1 * amount) * row_h;
    }
    out
}

fn time_avg<F: FnMut(usize)>(iters: usize, mut f: F) -> (f64, f64) {
    let mut worst = 0.0f64;
    let start = Instant::now();
    for i in 0..iters {
        let t = Instant::now();
        f(i);
        worst = worst.max(t.elapsed().as_secs_f64() * 1000.0);
    }
    (start.elapsed().as_secs_f64() * 1000.0 / iters as f64, worst)
}

fn main() {
    let path = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../app/src/main/assets/dictionary.bin");
    let t = Instant::now();
    let dicts = match dictionary::load_file(&path) {
        Ok(d) => d,
        Err(e) => {
            eprintln!("Slovnik {} nelze nacist ({e}). Spust scripts/download-dictionary.py.", path.display());
            return;
        }
    };
    let load_ms = t.elapsed().as_secs_f64() * 1000.0;
    let size_mb = std::fs::metadata(&path).map(|m| m.len() as f64 / 1e6).unwrap_or(0.0);
    println!("slovnik: {:.1} MB, nacteni {:.1} ms", size_mb, load_ms);
    for d in &dicts {
        println!("  {}: {} slov", d.lang, d.len());
    }
    let cs: &Dictionary = dicts.iter().find(|d| d.lang == "cs").expect("cs");
    let en: &Dictionary = dicts.iter().find(|d| d.lang == "en").expect("en");
    let user = UserDict::new();

    // --- dictionary lookup
    let encoded: Vec<Vec<u8>> = CS_WORDS.iter().filter_map(|w| charmap::encode_word(w)).collect();
    let n = 200_000;
    let t = Instant::now();
    let mut hits = 0usize;
    for i in 0..n {
        hits += cs.lookup(black_box(&encoded[i % encoded.len()])).is_some() as usize;
    }
    println!("dictionary lookup: {:.0} ns / dotaz ({} zasahu z {})", t.elapsed().as_nanos() as f64 / n as f64, hits, n);

    // --- swipe decode (S25 Ultra: sirka 1440 px, 5 radku klaves)
    let layout_cz = Layout::build(1440.0, 1050.0, true, MODE_LETTERS, true, "x");
    let layout_en = Layout::build(1440.0, 1050.0, false, MODE_LETTERS, true, "x");
    let (g_cz, g_en) = (layout_cz.geom(), layout_en.geom());
    let mut dec = SwipeDecoder::new();
    let mut rng = Lcg(42);

    let cs_paths: Vec<Vec<f32>> = CS_WORDS.iter().map(|w| noisy(&ideal_path(w, &g_cz, 14), g_cz.unit_w, g_cz.row_h, 0.12, &mut rng)).collect();
    let en_paths: Vec<Vec<f32>> = EN_WORDS.iter().map(|w| noisy(&ideal_path(w, &g_en, 14), g_en.unit_w, g_en.row_h, 0.12, &mut rng)).collect();

    let (avg, worst) = time_avg(500, |i| {
        black_box(dec.decode(&cs_paths[i % cs_paths.len()], &g_cz, &[cs], &user, 1.0, NOW));
    });
    println!("swipe decode (cs):      prumer {:.2} ms, nejhorsi {:.2} ms", avg, worst);
    let (avg, worst) = time_avg(500, |i| {
        black_box(dec.decode(&cs_paths[i % cs_paths.len()], &g_cz, &[cs, en], &user, 1.0, NOW));
    });
    println!("swipe decode (cs+en):   prumer {:.2} ms, nejhorsi {:.2} ms", avg, worst);

    // --- presnost swipe
    for (label, words, paths, geom, ds) in [
        ("cs", CS_WORDS, &cs_paths, &g_cz, vec![cs]),
        ("en", EN_WORDS, &en_paths, &g_en, vec![en]),
        ("cs v rezimu cs+en", CS_WORDS, &cs_paths, &g_cz, vec![cs, en]),
    ] {
        let (mut top1, mut top3) = (0, 0);
        let mut missed = Vec::new();
        for (w, p) in words.iter().zip(paths.iter()) {
            let res = dec.decode(p, geom, &ds, &user, 1.0, NOW);
            let pos = res.iter().position(|c| c.word == *w);
            match pos {
                Some(0) => {
                    top1 += 1;
                    top3 += 1;
                }
                Some(_) => top3 += 1,
                None => {
                    let folded = charmap::fold_bytes(&charmap::encode_word(w).unwrap_or_default());
                    let exact = keyboard_core::swipe::align_cost(&folded, dec.last_resampled(), geom, 1.0).map(|c| c / 64.0);
                    missed.push(format!(
                        "{w} (presna cena {exact:?}) -> {:?}",
                        res.iter().map(|c| format!("{} {:.3}", c.word, c.cost)).collect::<Vec<_>>()
                    ))
                }
            }
        }
        println!("presnost swipe ({label}): top1 {top1}/{n}, top3 {top3}/{n}", n = words.len());
        for m in missed.iter().take(6) {
            println!("    mimo top3: {m}");
        }
    }

    // --- presnost na sirsim vzorku castych slov ze slovniku (kdyz jsou k dispozici TSV)
    let tsv = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../dictionaries/build/cs_words.tsv");
    if let Ok(text) = std::fs::read_to_string(&tsv) {
        let sample: Vec<&str> = text
            .lines()
            .filter_map(|l| {
                let mut p = l.split(char::from(9u8));
                let w = p.next()?;
                let f: u32 = p.next()?.parse().ok()?;
                (f >= 150 && w.chars().count() >= 4).then_some(w)
            })
            .step_by(3)
            .take(600)
            .collect();
        for amount in [0.0f32, 0.12, 0.2] {
            let (mut top1, mut top3) = (0, 0);
            for w in &sample {
                let p = noisy(&ideal_path(w, &g_cz, 14), g_cz.unit_w, g_cz.row_h, amount, &mut rng);
                let res = dec.decode(&p, &g_cz, &[cs, en], &user, 1.0, NOW);
                match res.iter().position(|c| c.word.to_lowercase() == *w) {
                    Some(0) => {
                        top1 += 1;
                        top3 += 1;
                    }
                    Some(_) => top3 += 1,
                    None => {}
                }
            }
            println!("presnost na {} castych slovech (cs+en, sum {amount}): top1 {top1}, top3 {top3}", sample.len());
        }
    }

    // --- navrhy pri psani a predikce
    let both = [cs, en];
    let ctx = Context { dicts: &both, user: &user, now: NOW, autocorrect: true, predictions: true };
    let typed = ["p", "pr", "pri", "pril", "prilis", "klavesnicw", "dekuju", "nasledanou", "keybord", "tomorow", "nejneobhospodarovavatelnejsi"];
    let (avg, worst) = time_avg(550, |i| {
        black_box(predict::suggest_composing(&ctx, typed[i % typed.len()]));
    });
    println!("navrhy pri psani:       prumer {:.2} ms, nejhorsi {:.2} ms", avg, worst);
    let prevs = ["dobrý", "na", "jsem", "the", "of", "neznameslovo", ""];
    let (avg, worst) = time_avg(700, |i| {
        black_box(predict::predict_next(&ctx, "jak", prevs[i % prevs.len()], false));
    });
    println!("predikce dalsiho slova: prumer {:.3} ms, nejhorsi {:.3} ms", avg, worst);

    println!("ukazky:");
    for t in ["prilis", "dekuju", "klavesnicw", "tomorow"] {
        println!("  \"{t}\" -> {:?}", predict::suggest_composing(&ctx, t));
    }
    for p in ["dobrý", "jsem", "thank"] {
        println!("  po \"{p}\" -> {:?}", predict::predict_next(&ctx, "", p, false));
    }
    for (a, b) in [("jak", "se"), ("v", "roce"), ("na", "konci"), ("i", "don't")] {
        println!("  po \"{a} {b}\" -> {:?}", predict::predict_next(&ctx, a, b, false));
    }
}
