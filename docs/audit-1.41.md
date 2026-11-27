# Audit a měření (verze 1.37–1.41)

Shrnutí kontroly kódu, výkonu a prvního měření na skutečném telefonu. Stav k verzi **1.41** (2026-11-27).

## 1. Je to kompaktní a rychlá klávesnice?

- **Kompaktní: ano.** Release APK má asi 13 MB (obsahuje slovníky a nativní knihovnu pro arm64 i x86_64), žádný AndroidX, žádné XML layouty,
  žádný internet a jen dvě oprávnění (`VIBRATE`, `RECORD_AUDIO`). Veškerá logika je v jednom Rust jádru, Kotlin je tenká slupka.
- **Rychlá: podle prvního měření ano.** Viz měření níž (medián snímku 7 ms, 1,98 % zaseknutých snímků). Měření ale proběhlo v nastavení
  vzhledu s živým náhledem, ne při dlouhém psaní, a swipe latenci jsem na telefonu neměřil. Co je opravdu rychlé při psaní, je potřeba ještě změřit.
- **Neověřeno na telefonu:** latence swipe dekodéru, spotřeba baterie, paměť při dlouhém psaní, chování v různých aplikacích.

## 2. Naměřeno (telefon 1440×3120, `dumpsys gfxinfo cz.reallyfast`, 2026-11-27)

| Veličina | Hodnota |
|---|---|
| Vykreslených snímků | 122 673 |
| Zaseknuté snímky (janky) | 2 432 (**1,98 %**) |
| Medián / 90. / 95. / 99. percentil snímku | **7 ms** / 10 ms / 13 ms / 19 ms |
| GPU medián / 99. percentil | 4 ms / 12 ms |
| Pomalé vlákno UI | 755 snímků |
| Paměť GPU / CPU cache písma | 7,1 MB / 18,2 MB |

Z histogramu je vidět, že většina snímků trvá 5–10 ms, tedy v rozpočtu 120 Hz displeje (8,3 ms) nebo těsně nad ním. Dlouhý ocásek (20 ms a víc)
tvoří necelé procento snímků. Měřilo se hlavně v `AppearanceActivity` (živý náhled s animacemi), proto tato čísla neříkají nic o psaní v jiných aplikacích.

**Co se nepovedlo:** `log.txt` s ladicími řádky `fAZT` se nevytvořil (chyba testovacího skriptu, opraveno ve verzi 1.40), takže oprava zdvojeného
slova po zalomení řádku ani po odeslání zprávy v Messengeru zatím není ověřená z logu.

## 3. Hotové úpravy z auditu

- Osobní slova se v Rustu kódují jen jednou (mezipaměť v `UserDict`), takže návrhy a swipe už je při každém dotazu nepřekódovávají.
- Kreslení: odstraněna alokace `RectF` v `KeyboardView`, mrtvý kód (nepoužité členy a 38 nepoužitých řetězců), pomocné funkce pro text před kurzorem.
- Oprava zdvojeného slova: ochrana v `onSuggestionPicked`, zahození rozepsaného slova, které už v poli není (`dropStaleComposing`), logování `fAZT`.
- Obrys znaku (Waiting) zachovává průhlednost při animacích a neztrácí barvu obrysu, když má znak přechod.

## 4. Možné zrychlení (zatím nehotové)

1. **APK jen pro arm64** (nebo rozdělené APK): ušetří asi třetinu velikosti, x86_64 je jen pro emulátor.
2. **`panic = "abort"`** v release profilu Rustu: menší a rychlejší knihovna; předtím zkontrolovat, že `jni.rs` nespoléhá na `catch_unwind`.
3. **Slovníky přes `mmap`** a líné načítání druhého jazyka: rychlejší start a menší paměť.
4. **`Pool::add` v `predict.rs`** je O(k²) kvůli porovnávání malých písmen; při 12–20 kandidátech nevadí, ale jde zrychlit mapou.
5. **Ladění kreslení klávesy**: bloky pro „půl klávesy“ se opakují, dají se sloučit do jedné funkce.
6. **Cache návrhů**: při rychlém psaní se návrhy počítají po každém písmenu; stačí zpoždění o několik ms, nebo přerušení starého výpočtu.

## 5. Nápady na zlepšení

- Opravy překlepů se zatím nabízejí, ale nepřepisují samy (kromě doplnění diakritiky při mezerníku, které je teď volitelné).
- Víc návrhů po podržení (hotovo ve verzi 1.41: dvě další slova nad listou).
- Statistika psaní (rychlost, přesnost) jen v telefonu, bez odesílání.
- Export a import stylů zvlášť (od 1.40 se při obnově ze zálohy slučují).
- Automatický test výkonu: benchmark návrhů a swipe na pevné sadě slov v CI (jádro má benchmarky, JVM test mostu zatím žádný).

## 6. Testy

| Sada | Počet | Stav |
|---|---|---|
| Rust `cargo test --lib` | 211 | prošly |
| JVM (`:app:testDebugUnitTest`: most, kódy `OP_*`, odemykání) | 20 | prošly |

Nové testy z těchto verzí: osobní slova a pořadí návrhů, doplnění diakritiky (`auto_accent`), učení z vybraných oprav, rozšířený seznam návrhů,
sloučení stylů při obnově ze zálohy, přesná podoba výchozích rozložení kláves ve všech režimech.
Testy neověřují vzhled na displeji ani gesta; ty zůstávají na ručním projití na telefonu.
