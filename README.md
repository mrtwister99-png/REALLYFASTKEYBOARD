# reallyFAST! – soukromá swipe klávesnice

Offline klávesnice pro Android (cíleno na Samsung Galaxy S25 Ultra, One UI). Logika je v Rustu
(`crates/keyboard-core`), Kotlin je tenká slupka (`InputMethodService`, Canvas, haptika).
Žádný internet, žádná reklama, žádná telemetrie – jediné oprávnění je `VIBRATE`.

**Stav: Fáze 1 (funkce) + Fáze 2 (vzhled) hotové.** Identifikátor balíčku je `cz.reallyfast`
(název aplikace „reallyFAST!“ – vykřičník může být jen v zobrazovaném názvu, ne v ID balíčku).
Pokud máš v telefonu starší build „RustBoard“ (`cz.rustboard`), odinstaluj ho – systém ho bere jako jinou aplikaci.

## 1. Otevření v Android Studiu

1. Android Studio → **File > Open** → vyber tuto složku (root projektu).
2. Počkej na Gradle sync (Gradle 8.14.3, AGP 8.11.0, Kotlin 2.1.20, compileSdk 34, JDK 17+).
3. Nic dalšího není potřeba: přeložené Rust knihovny jsou v `app/src/main/jniLibs/`
   a slovník v `app/src/main/assets/dictionary.bin`.

Pokud je na PATH `cargo` + `cargo-ndk` a v SDK je NDK, Gradle před buildem Rust jádro sám přeloží
znovu (jen když se změnil Rust kód). Vypnutí: do `gradle.properties` přidej `reallyfast.buildRust=false`.

## 2. Sestavení APK

**Build > Build App Bundle(s) / APK(s) > Build APK(s)**

Výsledek: `app/build/outputs/apk/debug/app-debug.apk` (cca 11 MB).
Z příkazové řádky: `gradlew.bat :app:assembleDebug` (release: `:app:assembleRelease`, podepsáno debug klíčem).

## 3. Instalace do telefonu

1. Připoj telefon kabelem a přetáhni `app-debug.apk` do telefonu (např. do složky Download).
2. V telefonu otevři soubor v aplikaci Moje soubory a potvrď instalaci
   (při prvním použití povol „Instalovat neznámé aplikace“ pro Moje soubory).
   Alternativa: `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

## 4. Povolení klávesnice

Nejrychleji: otevři aplikaci **reallyFAST!** → *1. Povolit klávesnici v systému* → *2. Vybrat reallyFAST! jako aktivní*.

Ručně: **Nastavení > Systém > Jazyky > Klávesnice na obrazovce** (na One UI:
**Nastavení > Obecná správa > Seznam klávesnic a výchozí**) → zapni reallyFAST! → nastav jako výchozí.
Android zobrazí standardní varování pro klávesnice třetích stran; reallyFAST! nemá oprávnění k internetu,
takže nic odeslat nemůže.

## 5. Co klávesnice umí

| Oblast | Funkce |
|---|---|
| Tap | CZ QWERTZ / EN QWERTY, Shift (tap = jedno písmeno, double tap = Caps Lock), dlouhý stisk = diakritika (a→á, e→é ě, u→ú ů …), zrychlující se backspace, psaní dvěma palci |
| Swipe | 64 bodů, beam search (šířka 50) přes trie, skóre 0.6 geometrie + 0.25 jazyk + 0.15 osobní frekvence, 3 návrhy, diakritika se doplní sama, citlivost 0.5–1.5 |
| Návrhy | doplňování slova, oprava překlepů (edit distance ≤ 2, nikdy automaticky), doplnění diakritiky, predikce dalšího slova z bigramů |
| Učení | osobní slovník v SQLite (počet + čas použití), osobní bigramy, zapomínání, mazání slov, export/import JSON, vypínač |
| Gesta | tah vlevo z ⌫ = smazat slovo, tah po mezerníku = kurzor (rychlý švih = o slovo), švih nahoru = Caps, švih dolů = schovat, pinch = výška; vše vypínatelné |
| Jazyk | globus nebo dlouhý stisk mezerníku: Čeština → English → Čeština · English |
| Schránka | historie 30 položek, automatické mazání (výchozí 60 min), připnutí, smazání všeho, hesla a kódy se neukládají |
| Emoji | nedávné, smajlíci, zvířata, jídlo, aktivity, symboly + hledání podle názvu (česky i anglicky) |
| Symboly | čísla, závorky, měny, matematika (2 stránky) |
| Nastavení | vše ze zadání + vlastní barvy tématu, přepínače gest a schránky; uloženo v `settings.json` + záloha v SharedPreferences |
| Vzhled (Fáze 2) | 8 hotových stylů (Graphite, Paper, OLED, Midnight, Forest, Sand, Rose, Mono) + vlastní barvy, zaoblení kláves 2–22 dp, jemný stín, bublina s písmenem nad klávesou, nápovědy znaků dlouhého stisku, zužující se stopa swipe s dozníváním, ikona Enteru podle akce pole (hledat / odeslat / další / hotovo), prolínání panelů a návrhů, adaptivní ikona; animace lze vypnout |
| Přístupnost | TalkBack (popis každé klávesy), S Pen (hover nic nestiskne), landscape, split screen |

## 6. Struktura

```
app/                               Android aplikace (Kotlin, bez AndroidX)
  src/main/java/cz/reallyfast/
    KeyboardService.kt             InputMethodService, InputConnection, haptika
    KeyboardView.kt                vykreslení kláves (Canvas), dotyk, TalkBack
    Panels.kt                      lišta návrhů, emoji panel, panel schránky
    SettingsActivity.kt            nastavení
    RustBridge.kt                  JNI wrapper
    Settings.kt                    pohled na nastavení + barvy tématu
  src/main/jniLibs/                přeložené libkeyboard_core.so (arm64-v8a, x86_64)
  src/main/assets/dictionary.bin   slovník (FST)
  src/test/.../RustBridgeTest.kt   JVM test JNI mostu
crates/keyboard-core/              Rust jádro
  src/layout.rs  swipe.rs  dictionary.rs  predict.rs  settings.rs
      gestures.rs  storage.rs  learning.rs  clipboard.rs  emoji.rs  charmap.rs  jni.rs  lib.rs
  src/bin/build_dict.rs            sestavení dictionary.bin z TSV
  benches/core.rs                  benchmarky
dictionaries/                      zdrojová data slovníku (cs_CZ.dic, cs_CZ.aff, czech_freq_15k.txt)
scripts/download-dictionary.py     stažení korpusů + sestavení slovníku
scripts/ci.sh, scripts/ci.ps1      cargo test (CI)
```

Závislosti Rustu: `serde`, `serde_json`, `rusqlite` (bundled SQLite), `fst`. JNI je psané ručně
(bez crate `jni`), `unsafe` je jen v `src/jni.rs`.

## 7. Slovník

`python scripts/download-dictionary.py` (potřebuje Python 3 a Rust):

| Zdroj | Použití | Licence |
|---|---|---|
| Hunspell `cs_CZ` (LibreOffice dictionaries) – `dictionaries/cs_CZ.dic/.aff` | správnost: expanze všech tvarů | GPL-2.0 |
| Frekvenční seznam lemmat ČNK – `dictionaries/czech_freq_15k.txt` (15 000 lemmat podle pořadí) | pořadí častosti | CC BY 4.0 |
| Leipzig Corpora `ces_news_2020_300K` | frekvence českých tvarů + bigramy | CC BY 4.0 |
| Leipzig Corpora `eng_news_2020_300K` | anglická slova, frekvence + bigramy | CC BY 4.0 |

Výsledek: **1 979 138 českých tvarů** a **58 100 anglických slov**, 7,6 MB. Slova bez frekvence mají
nízkou výchozí frekvenci. Slovník se načítá na pozadí; tap psaní funguje okamžitě.

Poznámky k odchylkám od zadání:
- Dodaný frekvenční seznam má 15 000 lemmat bez počtů (ne 50 000 s frekvencí), proto frekvence tvarů
  a bigramy doplňuje český korpus z Leipzig Corpora (stejný zdroj a licence jako u angličtiny).
- Anglický seznam z korpusu 300K má 58 100 slov s výskytem ≥ 2 (požadavek ≥ 50 000 splněn).

## 8. Testy a benchmarky

```
scripts/ci.sh                      # cargo test (101 testů)
scripts/ci.sh --bench --jni        # + benchmarky + JVM test JNI mostu
gradlew.bat :app:testDebugUnitTest # 7 testů JNI mostu (načte Rust jádro do JVM)
```

Benchmark `cargo bench` nad reálným slovníkem. **Měřeno na vývojovém PC (x86-64), ne na telefonu** –
na S25 Ultra čekej několikanásobně vyšší časy, stále s velkou rezervou proti limitům:

| Měření | Výsledek (PC) | Limit ze zadání |
|---|---|---|
| Načtení slovníku (7,6 MB) | 3,9 ms | neblokuje UI (běží na pozadí) |
| Dictionary lookup | 72 ns / dotaz | – |
| Swipe decode, čeština | průměr 0,66 ms, nejhorší 1,00 ms | 30 ms |
| Swipe decode, cs+en | průměr 1,21 ms, nejhorší 2,00 ms | 30 ms |
| Návrhy při psaní (doplnění + opravy) | průměr 0,43 ms, nejhorší 2,14 ms | 50 ms |
| Predikce dalšího slova | průměr 0,02 ms | 50 ms |
| Přesnost swipe, 600 častých slov, simulovaný tah se šumem | 1. místo 579–581, top 3 600/600 | – |

Přesnost je měřená na simulovaných tazích (ideální dráha + šum), ne na skutečném psaní prstem.
Vykreslení snímku, odezva tapu, start klávesnice a paměť **nebyly na zařízení změřeny** (bez telefonu
ani emulátoru); kód je na ně navržen (žádné alokace v `onDraw`, synchronní JNI, líné načtení slovníku).

## 9. Soukromí a bezpečnost

- Manifest obsahuje pouze `android.permission.VIBRATE`; žádná síťová knihovna v projektu není.
- Data jsou v privátním adresáři aplikace (`settings.json`, `reallyfast.db`), zálohy a přenos do jiného
  zařízení jsou vypnuté, při odinstalaci se vše smaže.
- Osobní slovník **není šifrován na úrovni aplikace**: SQLCipher vyžaduje křížovou kompilaci OpenSSL
  a ChaCha20Poly1305 další závislost mimo povolený seznam. Spoléhá se na sandbox Androidu a systémové
  šifrování úložiště (zdůvodnění je i v `storage.rs`).
- V polích pro hesla a v polích s příznakem „no personalized learning“ se nic neučí a swipe je vypnutý.

## 10. Známé limitace

- **Netestováno na reálném zařízení – včetně vzhledu Fáze 2** (animace, bubliny, stíny jsem neviděl na displeji). Ověřeno: build APK, 101 Rust testů, JNI most v JVM. Chování
  v aplikacích, vzhled na displeji a gesta je potřeba vyzkoušet na telefonu.
- Švih nahoru/dolů (Caps / schovat) musí být rychlý, dlouhý a téměř svislý; výjimečně se může splést
  se swipe psaním svislého slova. Lze vypnout.
- Dlouhý stisk mezerníku přepíná jazyk až po delší prodlevě (min. 500 ms), aby nekolidoval s tahem kurzoru.
- Swipe nezná slova mimo slovník, dokud se je nenaučí tapem; nepíše víceslovné výrazy („na shledanou“).
- Predikce je bigramová (jen podle předchozího slova), bez delšího kontextu.
- Automatická velká písmena se řídí příznaky textového pole; aplikace, které je nenastaví, je nedostanou.
- Historie schránky se plní, jen když je reallyFAST! aktivní klávesnice (omezení Androidu 10+).
- Emoji: cca 300 kusů, bez variant barvy pleti.
- Split/floating klávesnice pro foldables není; rozložení se jen přizpůsobí šířce.
- targetSdk 34 dle zadání – pro Google Play by bylo nutné zvýšit.

## 11. Co dál

- Doladění vzhledu podle skutečného dojmu na displeji (velikosti písma, kontrast, rychlost animací).
- Měření na S25 Ultra (frame time, paměť) a doladění vah swipe podle reálných tahů.
- Trigramy / lepší jazykový model, učení z oprav.
- Šifrování osobního slovníku klíčem z Android Keystore.
- Jednoruční režim, plovoucí klávesnice, více jazyků.
