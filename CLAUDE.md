# CLAUDE.md – pravidla projektu fAZT!

Android klávesnice `cz.reallyfast`: logika v Rustu (`crates/keyboard-core`), Kotlin je tenká slupka. Přehled je v [README.md](README.md),
architektura v [docs/architecture.md](docs/architecture.md), všechna nastavení v [docs/settings.md](docs/settings.md).
Uživatel píše česky a neformálně; odpovídej **česky**, stručně a věcně.

## Pravidla kódu (platí vždy)

- **Komentáře a texty v kódu česky BEZ diakritiky** (`// Vybarveni klavesy ...`). Diakritika patří jen do `res/values*/strings.xml`
  a do dokumentace v `docs/`, `README.md`, `CHANGELOG.md`.
- **Texty uživatelského rozhraní vždy ve dvou jazycích**: `app/src/main/res/values/strings.xml` (česky, s diakritikou) a
  `values-en/strings.xml` (anglicky). Nový řetězec bez obou verzí je chyba. V angličtině se apostrofy escapují (`isn\'t`) a `?` na začátku
  jako `\?123`.
- **Žádný AndroidX**, žádné XML layouty ani XML animace: obrazovky se skládají z obyčejných `View` v Kotlinu (viz `Ui.kt`, `SettingsScreen.kt`).
- **Žádné alokace v `onDraw`** (`KeyboardView`, `ToggleView`, `ColorPlaneView`, `Shoal` …): žádné `new`/lambdy/`Paint`/`Path`/`RectF`/`IntArray`
  při kreslení. Barvy a shadery se připravují při změně nastavení (`applyAppearance`, `updateMetrics`), ne při kreslení; pomocné objekty jsou členy třídy.
- **Žádný internet**: oprávnění `INTERNET` se nepřidává, žádná síťová knihovna. Nová oprávnění jen po dohodě.
- **Rust**: `unsafe` je jen v `src/jni.rs`; nové závislosti se nepřidávají bez dohody (`serde`, `serde_json`, `rusqlite`, `fst`).
- **Nastavení se validuje v Rustu** (`settings.rs`, `validate_fields`). Kotlin nikdy nevěří hodnotě z JSON slepě.
- **Kód, který jen uživateli „vykoukne“ nebo se odemyká zvláštním způsobem** (`Lantern`, `Tide`, `Shoal`, `Waiting`): pojmenování zůstává
  neutrální (žádná slova jako secret / unlock / hidden / troll v názvech a komentářích) a způsob odemknutí se **nikde nedokumentuje**
  (ani v README, ani v docs).
- Mezi Rustem a Kotlinem musí sedět: kódy `OP_*` (`lib.rs` ↔ `RustBridge.kt`), `MACRO_FACES` (`settings.rs` ↔ `MacroFaces.COUNT` v `Manage.kt`),
  poměry efektů a hodnoty výběrů (`tap_effect`, `glow_style`, `KEY_PLACES`, `THEMES`).

## Jak přidat nové nastavení (zkrácený seznam, podrobně v docs/architecture.md)

1. `settings.rs`: pole ve `struct Settings`, výchozí hodnota v `base_default()`, ořezání v `validate_fields()`; je-li to vzhled, přidat klíč do `STYLE_KEYS`.
2. Rust test v `settings.rs` (výchozí hodnota, ořezání, kulatá cesta `from_json`/`to_json`, členství v `STYLE_KEYS`).
3. `Settings.kt`: přístupová vlastnost. `KeyboardView.applyAppearance` / vykreslení, je-li to vzhled.
4. UI řádek v `Appearance.kt` (`buildMain` = úroveň 1, `buildAdvanced` = úroveň 2, `buildExtra` = úroveň 3 Waiting) nebo v `Typing.kt` / `Voice.kt`;
   v `onSettingUpdated` případně ukázka v náhledu; `autoColor` v `SettingsScreen.kt`, jde-li o barvu.
5. Řetězce `values` i `values-en`.
6. Přidat řádek do `docs/settings.md` a větu do `CHANGELOG.md`.

## Příkazy

Z kořene repozitáře (Windows: Git Bash nebo PowerShell):

```
cd crates/keyboard-core && cargo test --lib          # rychlé testy jádra (189)
cd crates/keyboard-core && cargo test --all-targets  # vše včetně bin a benchů (pomalejší)
./gradlew.bat :app:testDebugUnitTest :app:assembleDebug --console=plain   # JVM testy mostu + APK
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. Gradle při změně Rustu přeloží `libkeyboard_core.so` (cargo-ndk) a přepíše
`app/src/main/jniLibs/*/libkeyboard_core.so`; ty jsou v repozitáři a **patří do stejného commitu** jako změna Rustu.
Po buildu zkontroluj, že se `.so` opravdu změnilo (jinak by nová nastavení tiše nefungovala). Po každé změně Rustu spusť `cargo test`,
po každé změně Kotlinu build s JVM testy.

## Commity a verze

- Commituj a pushuj **jen na výslovné slovo uživatele** („commit a push“). Přímo na `main`.
- Zpráva: česky **bez diakritiky**, formát `vX.Y: co se zmenilo, dalsi co se zmenilo` (např. `v1.21: obrazek patri makru, novy maskot`).
  Menší opravy `vX.Y.Z: …`. Na konec patří řádek `Co-Authored-By: …` podle aktuálního modelu.
- `git push` na tomto stroji občas zamrzne (správce přihlašovacích údajů): pouštěj ho na pozadí a úspěch ověř přes `git ls-remote origin main`
  (shoda s `git rev-parse HEAD`). **Nikdy `--force`.**
- Při vydání verze přepiš `versionName` a `versionCode` v `app/build.gradle.kts`, přidej řádek do `CHANGELOG.md` a podle potřeby README.
- Nevytvářej pull requesty, pokud o to uživatel nepožádá.

## Poctivost o stavu

Nic z aplikace nebylo vyzkoušeno na skutečném telefonu. Při hlášení hotové práce vždy napiš, co je **ověřeno** (testy, build) a co **ne**
(vzhled, animace, gesta na displeji). Neslibuj nic, co jsi nespustil.

## Praktické poznámky pro Windows

- Soubory mají často konce řádků CRLF. Při úpravách skriptem zachovej konce řádků (načíst, `\r\n` → `\n`, upravit, vrátit zpět); `Edit`
  na jednořádkové úseky obvykle stačí.
- Konzole zobrazuje diakritiku špatně; soubory jsou UTF-8. Při generování souborů vždy zapisuj UTF-8.
- Dočasné soubory a skripty patří do scratchpadu relace, ne do repozitáře.

## Pracovní postup, který uživatel preferuje

Návrh a přesný postup nechává napsat silnějšímu modelu, implementaci dělá levnější model nebo podagent a výsledek se vždy
ověří (diff, testy, build) před hlášením hotovo. Větší změny rozděl na kroky: Rust a jeho testy, pak Kotlin, pak UI a řetězce, nakonec build.
