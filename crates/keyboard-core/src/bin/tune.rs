//! Offline ladeni vah swipe decoderu nad zaznamem skutecnych tahu.
//! Pouziti: tune <dictionary.bin> <swipes.json>
//!
//! Soubor swipes.json vznika v nastaveni klavesnice (Zaznam tahu -> Exportovat). Nastroj
//! prehraje tahy, u kterych je znamo spravne slovo (outcome 0 nebo 1), zmeri presnost
//! s vychozimi vahami, vypise chyby a zkusi mrizku vah.

use keyboard_core::dictionary::{self, Dictionary};
use keyboard_core::learning::UserDict;
use keyboard_core::storage::{SwipeLogFile, SwipeTrace};
use keyboard_core::swipe::{SwipeDecoder, Weights};
use std::path::Path;

/// Mene pouzitelnych tahu nez tohle je na zmenu vah malo.
const MIN_TRACES: usize = 30;
const MAX_MISSES: usize = 40;
const NOW: i64 = 1_800_000_000_000;

#[derive(Clone, Copy, Default)]
struct Score {
    top1: usize,
    top3: usize,
}

/// Prehraje vsechny tahy s danymi vahami. Chyby (pravda -> co se dekodovalo) se plni jen na pozadani.
fn replay(
    decoder: &mut SwipeDecoder,
    dicts: &[&Dictionary],
    traces: &[&SwipeTrace],
    weights: Weights,
    mut misses: Option<&mut Vec<String>>,
) -> Score {
    decoder.weights = weights;
    let user = UserDict::new();
    let mut score = Score::default();
    for t in traces {
        let truth = t.word.to_lowercase();
        let res = decoder.decode(&t.points, &t.geom, dicts, &user, 1.0, NOW);
        if res.first().is_some_and(|c| c.word.to_lowercase() == truth) {
            score.top1 += 1;
        } else if let Some(m) = misses.as_deref_mut() {
            let got = res.iter().map(|c| c.word.as_str()).collect::<Vec<_>>().join(", ");
            m.push(format!("{} -> {}", t.word, if got.is_empty() { "(nic)" } else { &got }));
        }
        if res.iter().any(|c| c.word.to_lowercase() == truth) {
            score.top3 += 1;
        }
    }
    score
}

fn percent(n: usize, total: usize) -> f32 {
    if total == 0 {
        0.0
    } else {
        100.0 * n as f32 / total as f32
    }
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    if args.len() != 3 {
        eprintln!("Pouziti: tune <dictionary.bin> <swipes.json>");
        std::process::exit(2);
    }
    let dicts = match dictionary::load_file(Path::new(&args[1])) {
        Ok(d) => d,
        Err(e) => {
            eprintln!("Slovnik {} nelze nacist ({e}).", args[1]);
            std::process::exit(1);
        }
    };
    let text = match std::fs::read_to_string(&args[2]) {
        Ok(t) => t,
        Err(e) => {
            eprintln!("Soubor {} nelze precist ({e}).", args[2]);
            std::process::exit(1);
        }
    };
    let file: SwipeLogFile = match serde_json::from_str(&text) {
        Ok(f) => f,
        Err(e) => {
            eprintln!("Soubor {} neni platny export tahu ({e}).", args[2]);
            std::process::exit(1);
        }
    };
    if file.app != "reallyFAST" {
        eprintln!("Soubor {} neni export z reallyFAST.", args[2]);
        std::process::exit(1);
    }

    let dict_refs: Vec<&Dictionary> = dicts.iter().collect();
    let traces: Vec<&SwipeTrace> =
        file.swipes.iter().filter(|t| (t.outcome == 0 || t.outcome == 1) && !t.word.is_empty()).collect();
    let rejected = file.swipes.iter().filter(|t| t.outcome == 2).count();
    println!("Tahu v souboru: {} (smazanych hned po tahu: {})", file.swipes.len(), rejected);
    println!("Pouzitelnych tahu (znama pravda): {}", traces.len());
    if traces.is_empty() {
        println!("Nic k prehrani.");
        return;
    }

    let mut decoder = SwipeDecoder::new();
    let mut misses = Vec::new();
    let base = replay(&mut decoder, &dict_refs, &traces, Weights::default(), Some(&mut misses));
    let n = traces.len();
    println!("\nVychozi vahy {:?}", Weights::default());
    println!("  top-1: {}/{} ({:.1} %)", base.top1, n, percent(base.top1, n));
    println!("  top-3: {}/{} ({:.1} %)", base.top3, n, percent(base.top3, n));

    println!("\nChyby (pravda -> dekodovano), max {MAX_MISSES}:");
    for m in misses.iter().take(MAX_MISSES) {
        println!("  {m}");
    }
    if misses.len() > MAX_MISSES {
        println!("  ... a dalsich {}", misses.len() - MAX_MISSES);
    }
    if misses.is_empty() {
        println!("  zadne");
    }

    if n < MIN_TRACES {
        println!(
            "\nVzorek je prilis maly ({n} < {MIN_TRACES} tahu) na to, aby se podle nej menily vahy. \
             Zaznamenej vic tahu a spust nastroj znovu."
        );
        return;
    }

    let mut results: Vec<(Weights, Score)> = Vec::new();
    for gi in 0..=6 {
        let geometry = 0.45 + 0.05 * gi as f32;
        for geo_k in [2.0f32, 2.5, 3.0, 3.5, 4.0] {
            let w = Weights { geometry, language: 0.85 - geometry, personal: 0.15, geo_k };
            results.push((w, replay(&mut decoder, &dict_refs, &traces, w, None)));
        }
    }
    results.sort_by(|a, b| b.1.top1.cmp(&a.1.top1).then(b.1.top3.cmp(&a.1.top3)));
    println!("\nMrizka vah ({} kombinaci), 5 nejlepsich podle top-1, pak top-3:", results.len());
    for (w, s) in results.iter().take(5) {
        println!(
            "  geometrie {:.2}  jazyk {:.2}  osobni {:.2}  geo_k {:.1}  ->  top-1 {:.1} %  top-3 {:.1} %",
            w.geometry,
            w.language,
            w.personal,
            w.geo_k,
            percent(s.top1, n),
            percent(s.top3, n)
        );
    }
}
