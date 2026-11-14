//! Postavi `dictionary.bin` z TSV souboru vytvorenych skriptem scripts/download-dictionary.py.
//! Pouziti: build-dict <slozka s TSV> <vystupni soubor>

use keyboard_core::dictionary::{write_file, DictData};
use std::fs;
use std::path::{Path, PathBuf};

fn read_words(path: &Path) -> std::io::Result<Vec<(String, u8, bool)>> {
    let text = fs::read_to_string(path)?;
    Ok(text
        .lines()
        .filter_map(|l| {
            let mut p = l.split('\t');
            let w = p.next()?;
            let f: u8 = p.next()?.trim().parse().ok()?;
            let cap = p.next().map(|c| c.trim() == "1").unwrap_or(false);
            Some((w.to_string(), f, cap))
        })
        .collect())
}

fn read_bigrams(path: &Path) -> std::io::Result<Vec<(String, String, u32)>> {
    let text = fs::read_to_string(path)?;
    Ok(text
        .lines()
        .filter_map(|l| {
            let mut p = l.split('\t');
            Some((p.next()?.to_string(), p.next()?.to_string(), p.next()?.trim().parse().ok()?))
        })
        .collect())
}

fn read_trigrams(path: &Path) -> Vec<(String, String, String, u32)> {
    // Soubor s trigramy je volitelny (starsi TSV ho nemaji).
    let Ok(text) = fs::read_to_string(path) else { return Vec::new() };
    text.lines()
        .filter_map(|l| {
            let mut p = l.split(char::from(9u8));
            Some((p.next()?.to_string(), p.next()?.to_string(), p.next()?.to_string(), p.next()?.trim().parse().ok()?))
        })
        .collect()
}

fn main() -> std::io::Result<()> {
    let args: Vec<String> = std::env::args().collect();
    if args.len() != 3 {
        eprintln!("pouziti: build-dict <slozka s TSV> <vystup>");
        std::process::exit(2);
    }
    let dir = PathBuf::from(&args[1]);
    let mut built = Vec::new();
    for lang in ["cs", "en"] {
        let words = read_words(&dir.join(format!("{lang}_words.tsv")))?;
        let bigrams = read_bigrams(&dir.join(format!("{lang}_bigrams.tsv")))?;
        let trigrams = read_trigrams(&dir.join(format!("{lang}_trigrams.tsv")));
        let data = DictData::build_with_trigrams(&words, &bigrams, &trigrams)?;
        println!(
            "{lang}: {} slov, {} bigramu -> fst {:.2} MB, swipe fst {:.2} MB, bigramy {:.2} MB, predpony {:.2} MB",
            words.len(),
            bigrams.len(),
            data.words.len() as f64 / 1e6,
            data.swipe.len() as f64 / 1e6,
            (data.bigram_idx.len() + data.bigram_blob.len()) as f64 / 1e6,
            (data.prefix_idx.len() + data.prefix_blob.len()) as f64 / 1e6
        );
        println!(
            "    trigramy: {} -> {:.2} MB",
            trigrams.len(),
            (data.trigram_idx.len() + data.trigram_blob.len()) as f64 / 1e6
        );
        built.push((lang, data));
    }
    let refs: Vec<(&str, &DictData)> = built.iter().map(|(l, d)| (*l, d)).collect();
    write_file(Path::new(&args[2]), &refs)?;
    println!("zapsano: {}", args[2]);
    Ok(())
}
