# Architektura fAZT!

Tři vrstvy. Rust drží **veškerou logiku a data**, Kotlin jen **kreslí, drží stav obrazovky a mluví s Androidem**, most mezi nimi je jedna funkce.

```
┌────────────────────────── Kotlin (app/) ───────────────────────────┐
│ KeyboardService  ── InputConnection, haptika, diktování, schránka  │
│ KeyboardView     ── Canvas: klávesy, efekty, dotyk, TalkBack       │
│ Panels           ── lišta návrhů, emoji, schránka                  │
│ Appearance & spol. ─ obrazovky nastavení, živý náhled              │
│ Settings / Theme ── pohled na JSON nastavení, barvy                │
└───────────────┬────────────────────────────────────────────────────┘
                │ RustBridge.call(OP_xxx, a, b, num)   (UTF-8 bajty)
┌───────────────┴──────────── JNI (jni.rs, jediné `unsafe`) ─────────┐
│ nativeCall(op, byte[], byte[], long) -> byte[]                     │
│ nativeSwipe(float[] body, count, flags) -> byte[]                  │
└───────────────┬────────────────────────────────────────────────────┘
┌───────────────┴──────────── Rust (crates/keyboard-core) ───────────┐
│ lib.rs `dispatch` → Engine: nastavení, rozložení, swipe, slovník,  │
│ predikce, učení, schránka, makra, hlasové povely, záloha, SQLite   │
└────────────────────────────────────────────────────────────────────┘
```

## Rust jádro (`crates/keyboard-core/src`)

| Modul | Co dělá |
|---|---|
| `lib.rs` | `Engine` (celý stav) a `dispatch(op, a, b, num)`, jediný vstupní bod pro Kotlin. Nikdy nepanikaří; neznámá operace vrací prázdnou odpověď. Kódy `OP_*` (1–50). |
| `settings.rs` | struktura `Settings` (93 nastavení), výchozí hodnoty, `validate`, styly 1–5 (`STYLE_KEYS`, `style_slots`). Jediný zdroj pravdy pro nastavení. |
| `layout.rs` | geometrie kláves (CZ QWERTZ / EN QWERTY, čísla, symboly) v pixelech. Kotlin ji jen kreslí a swipe decoder ji používá taky, takže se kreslení a dekódování nemůže rozejít. |
| `swipe.rs` | dráha → 64 bodů → sekvence nejbližších kláves → beam search (šířka 50) přes trie → skóre → top 3 |
| `dictionary.rs`, `charmap.rs` | vestavěný slovník (FST slovo → frekvence, bigramy, trigramy), jednobajtové kódování znaků |
| `predict.rs` | návrhy při psaní, doplnění diakritiky, oprava (edit distance ≤ 2), predikce dalšího slova |
| `learning.rs` | osobní slovník, bigramy a trigramy, opravy, zapomínání |
| `gestures.rs` | smazání slova, posun kurzoru, Caps, schování, pinch, Shift, velká písmena, dvojitá mezera |
| `clipboard.rs`, `emoji.rs` | pravidla schránky (limit, expirace, detekce hesel), kategorie a hledání emoji |
| `macros.rs`, `voice.rs` | makra a zkratky (`{datum}`, `{cas}`), hlasové povely pro diktování |
| `storage.rs` | `settings.json` + SQLite `reallyfast.db` (tabulky `user_words`, `user_bigrams`, `user_trigrams`, `corrections`, `clipboard`, `emoji_recent`, `macros`, `voice_commands`, `key_calibration`, `swipe_log`) |
| `backup.rs` | formát úplné zálohy do jednoho JSON |
| `jni.rs` | hranice JNI, psané ručně bez crate `jni` (jen pět funkcí z tabulky `JNINativeInterface`) |
| `bin/build_dict.rs`, `bin/tune.rs` | sestavení slovníku, offline ladění vah swipe |

### Protokol mostu

`RustBridge.call(op, a, b, num)` pošle `byte[]` v UTF-8 (řetězce, JSON) a `long`, vrátí `byte[]` s odpovědí (nejčastěji JSON nebo řádky
oddělené `\n`). Žádné objekty přes hranici nechodí. Důsledek: nový kus funkcionality v Rustu = nový `OP_*` v `lib.rs`, stejné číslo v `RustBridge.kt`
(`private const val OP_… `), větev v `dispatch` a tenká funkce v `RustBridge`. **Čísla musí sedět**, neexistuje kontrola při překladu.
Swipe má vlastní vstup `nativeSwipe` (pole `float` x, y, t a příznaky).

### Start a životní cyklus

1. `RustBridge.init(context)` (volá `KeyboardService` i obrazovky nastavení; je idempotentní): načte knihovnu, `OP_INIT` s adresářem `filesDir`,
   načte nastavení. Když `settings.json` ještě neexistuje, obnoví ho ze záložní kopie v SharedPreferences.
2. Slovník (`dictionary.bin` z assets) se na pozadí zkopíruje do `filesDir` a načte (`OP_LOAD_DICT`). Psaní tapem funguje hned; návrhy a swipe až po načtení.
3. Po každé změně nastavení a po načtení slovníku se zavolají `RustBridge.listeners` na hlavním vlákně (klávesnice a obrazovky se překreslí).

## Tok nastavení (nejdůležitější cesta v celém projektu)

```
UI řádek (SettingsScreen.update / sliderRow / colorRow …)
   └─ j = RustBridge.settings.edit()  (kopie JSON)  →  j.put(klíč, hodnota)
        └─ RustBridge.setSettings(j)  →  OP_SET_SETTINGS
             └─ Engine.set_settings:  Settings::from_json → validate → uložit settings.json
                  └─ odpověď = platný JSON  →  Settings(JSON) v Kotlinu + kopie do SharedPreferences
                       └─ listeners → KeyboardView.reloadLayout → applyAppearance → updateMetrics → onDraw
```

Hodnotu tedy **vždy rozhoduje Rust** (`validate`): nepovolená volba se nahradí výchozí, číslo se ořízne, barva se opraví nebo vyprázdní. UI se po zápisu
vždy znovu přečte z odpovědi (`refreshers` v `SettingsScreen`), takže zobrazuje to, co je skutečně uloženo.

## Vykreslení (`KeyboardView`)

- `reloadLayout()` → `OP_LAYOUT` vrátí geometrii kláves → `Key` objekty.
- `applyAppearance()` přečte nastavení do polí (barvy, přepínače, `Theme`) a `updateMetrics()` z nich postaví shadery (přechody kláves, znaků, pozadí, obrysu).
  **Cokoli, co se může spočítat dopředu, se počítá tady, ne v `onDraw`.**
- `onDraw` vykreslí pozadí, klávesy (barva klávesy + efekty po klepnutí: Neon, Glitch, Barvy, Duch, Obvod, Výbuch, na znakových klávesách i na mezerníku; rozsvícení po tahu), obrys, znaky, nápovědy, stopu, bublinu,
  animaci Slovo. Stavy kláves (`pressed`, `passed`, `tapAt`, `passedFadeAt`, `neonAt`, `ghostAt`, `orbitAt`, `popAt`) se mění v dotykové logice; v `onDraw` se jen čtou a časují.
  Dokud něco běží, `onDraw` nastaví `animating = true` a vyžádá další snímek.
- `Theme` (v `Settings.kt`) počítá z nastavení všechny odvozené barvy (`keyPressed`, `popup`, `hint`, `barText` …). Barva textu nad klávesnicí (`barText`) se
  volí podle kontrastu s pozadím (WCAG).
- Duha: hodnota barvy `"rainbow"` se v Rustu povolí jen u vyjmenovaných nastavení; Kotlin ji rozpozná (`Theme.RAINBOW`) a postaví gradient přes `Theme.RAINBOW_COLORS`.

## Obrazovky nastavení

- `SettingsActivity` (hlavní: aktivace, rozcestník, schránka, záloha), `AppearanceActivity` (`Appearance.kt`: Vzhled a rozložení), `TypingActivity`, `VoiceActivity`,
  `DictionaryActivity`, `MacrosActivity`, `AboutActivity`. Všechny dědí z `BaseScreen` (`Ui.kt`: barvy, dialogy, zpět); obrazovky s řádky voleb ze `SettingsScreen`, ostatní podobrazovky ze `SubScreen` (`Manage.kt`). Je to jen kód v Kotlinu, žádné layouty.
- **`Appearance.kt`** má dvě záložky: `buildMain` (úroveň 1: základ; pod ním `buildAdvanced` = úroveň 2 ve sbalené kartě „Pokročilé“) a `buildExtra` (úroveň 3: záložka Waiting). Nadpis, záložky a výběr stylů 1–5 jsou připnuté nahoře mimo posuvnou část; nad náhledem je řádek Zpět / Uložit. Zpět drží historii snímků nastavení (`undoStack`, nejvýš 50 kroků, změny blíž než 700 ms tvoří jeden krok).
  Nahoře je živý náhled (`KeyboardView` v režimu `preview`), který ukazuje každou změnu; `onSettingUpdated` spouští krátké ukázky (`demoNeon`, `demoTrail`, …).
  Součástí je editor klávesy (bublina), nastavování polohy znaku a nápovědy, výběr kláves k prohození a přiřazení maker.
- Stavební prvky: `switchRow`, `valueSwitchRow`, `sliderRow`, `choiceRow`, `colorRow` (s barevným kolem, volitelně „Výchozí“ a „Duha“, volitelně `dependsOn`),
  `buttonRow`, `navRow`, `confirmDialog`.
- **Zápisy do SQLite**: `Engine::learn()` zapisuje slovo, bigram, trigram a opravu v jedné transakci (`Storage::batch`).
- **Styly 1–5**: pole `style_slots` v nastavení; ukládání a načítání dělá Rust (`OP_STYLE_SAVE` / `OP_STYLE_LOAD`), takže seznam klíčů vzhledu (`STYLE_KEYS`) existuje jen na jednom místě.
  Klíče, které do stylu patří, se přidávají do `STYLE_KEYS` (jinak se nový vzhledový prvek do stylů neuloží).

## Makra a obrázky

- Texty maker jsou v SQLite (`macros`), na klávesy se přiřazují přes `key_macros` (klik) a `key_macros_hold` (podržení) – klíč je původní znak pozice, hodnota id makra.
- Obrázek makra patří **makru**, ne klávese: mapa `macro_faces` (id makra → 0 trol, 1 chocho, 2 dollo, 3 maskot), volí se v editoru makra. Číslo i pořadí
  musí sedět mezi `MACRO_FACES` (`settings.rs`), `MacroFaces.IMAGES` (`Manage.kt`) a `faceBitmaps` (`KeyboardView.kt`).
- Smazání makra (`RustBridge.deleteMacro`) smaže i jeho přiřazení klávesám a jeho obrázek.

## Záloha

`OP_BACKUP_EXPORT` / `OP_BACKUP_IMPORT` (`backup.rs`, `Engine::export_backup` / `import_backup`). Import nastavení **přepíše**, slovník, opravy a makra **sloučí**.
Zálohy bez pole `style_slots` (starší verze) sloty stylů v telefonu **zachovají**. Schránka a nedávná emoji se nezálohují záměrně.

## Jak přidat nové nastavení (podrobně)

Příklad: nová barva `foo_color` (volitelná, výchozí prázdná, patří do vzhledu úrovně 2).

1. **`settings.rs`**
   - pole `pub foo_color: String` ve `struct Settings` s komentářem `///`;
   - výchozí hodnota `foo_color: String::new()` v `base_default()`;
   - v `validate_fields()`: `fix_optional_color(&mut self.foo_color);` (nebo `fix_optional_color_or_rainbow`, `fix_color`, `clamp`, kontrola výběru);
   - je-li to vzhled: přidat `"foo_color"` do `STYLE_KEYS`.
2. **Test** v `mod tests` v `settings.rs`: výchozí hodnota, ořezání špatné hodnoty, kulatá cesta `Settings::from_json(&s.to_json()) == s`,
   `STYLE_KEYS.contains(..)`. Spustit `cargo test --lib`. (Existující testy `style_keys_exist_and_default_slots_are_full` a `style_slots_are_normalized` hlídají, že klíče ze `STYLE_KEYS` skutečně existují.)
3. **`Settings.kt`**: `val fooColor: String get() = json.optString("foo_color", "")`.
4. **Použití**: pro vzhled `KeyboardView.applyAppearance()` (načíst do pole, případně sestavit shader v `updateMetrics()`) a kreslení v `onDraw` (bez alokací).
5. **UI řádek** v `Appearance.kt`: `colorRow(box, R.string.foo_color, "foo_color", allowAuto = true)` v `buildMain` / `buildAdvanced` / `buildExtra` podle úrovně;
   pro barvy doplnit `autoColor` v `SettingsScreen.kt` (barva, která se v kole ukáže u „Výchozí“); v `onSettingUpdated` přidat klíč k ukázce v náhledu.
6. **Řetězce** `foo_color` (a případně `foo_color_hint`) do `values/strings.xml` a `values-en/strings.xml`.
7. **Build** (`./gradlew.bat :app:testDebugUnitTest :app:assembleDebug`) a kontrola, že se `jniLibs/*/libkeyboard_core.so` změnilo.
8. **Dokumentace**: řádek do [settings.md](settings.md), věta do [CHANGELOG](../CHANGELOG.md).

Nejčastější chyby: zapomenutý klíč ve `STYLE_KEYS`; česky bez anglického řetězce; alokace v `onDraw`; nezměněné `.so` (nové klíče by se tiše zahazovaly);
změněné číslo `OP_*` jen na jedné straně.

## Ostatní drobnosti

Menší doplňkové moduly (`Lantern.kt`, `Tide.kt`, `Shoal.kt`) mají záměrně neutrální pojmenování a nejsou součástí uživatelské dokumentace.
Pravidla pro jejich údržbu jsou v [CLAUDE.md](../CLAUDE.md).
