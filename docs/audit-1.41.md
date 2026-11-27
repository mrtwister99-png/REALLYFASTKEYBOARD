# Audit a měření (verze 1.37–1.41)

Shrnutí kontroly kódu, výkonu a prvního měření na skutečném telefonu. Stav k verzi **1.41** (2026-11-27).

## 1. Je to kompaktní a rychlá klávesnice?

- **Kompaktní: ano.** Release APK má asi 13 MB (obsahoval slovníky a nativní knihovnu pro arm64 i x86_64; od 1.44 jen arm64), žádný AndroidX, žádné XML layouty,
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

## 4. Zrychlení a rozhodnutí

Hotovo ve verzi 1.44:

1. **APK jen pro arm64**: knihovna pro x86_64 (emulátor) je pryč z buildu i z repozitáře, APK má asi 9,9 MB místo 13,3 MB.
2. **Zpoždění výpočtu návrhů**: při psaní slova se návrhy počítají 35 ms po posledním písmenu, takže při rychlém psaní zbyde jeden výpočet místo jednoho po každém písmenu.
3. **`Pool::add`**: sběr kandidátů používá hash mapu s klíčem malými písmeny a `top` vybírá nejlepší bez řazení všeho. Dřív bylo hledání duplikátů kvadratické,
   což by u velkého osobního slovníku (tisíce kandidátů na krátkou předponu) bolelo. Výsledky návrhů jsou stejné (test), rychlost běžných návrhů se nezhoršila
   (0,19 ms průměr).

Rozhodnuto nedělat (zatím):

- **`panic = "abort"`**: `jni.rs` chytá chyby jádra přes `catch_unwind` (obecné volání i swipe) a vrací prázdný výsledek, klávesnice jede dál. S `abort` by každá chyba
  ukončila celý proces. Úspora je jen pár stovek kB, riziko je větší.
- **Slovníky přes `mmap`**: načtení `dictionary.bin` (9,3 MB, cs 1,98 mil. slov, en 58 tis.) trvá na PC 3,3 ms, na telefonu řádově desítky ms při startu a drží asi 9 MB paměti.
  `mmap` by to zkrátil o pár ms a ušetřil paměť, ale vyžaduje novou závislost (`memmap2`), `unsafe` mimo `jni.rs` a rozbalení souboru z `assets` na disk.
  Vyplatí se, až slovníky povyrostou (další jazyky, desítky MB). Do té doby se nevyplatí.

## 5. Nápady na zlepšení

- Opravy překlepů se zatím nabízejí, ale nepřepisují samy (kromě doplnění diakritiky při mezerníku, které je teď volitelné).
- Víc návrhů po podržení (hotovo ve verzi 1.41: dvě další slova nad listou).
- Statistika psaní (rychlost, přesnost) jen v telefonu, bez odesílání.
- Export a import stylů zvlášť (od 1.40 se při obnově ze zálohy slučují).
- Automatický test výkonu: benchmark návrhů a swipe na pevné sadě slov v CI (jádro má benchmarky, JVM test mostu zatím žádný).

## 6. Testy

| Sada | Počet | Stav |
|---|---|---|
| Rust `cargo test --lib` | 213 | prošly |
| JVM (`:app:testDebugUnitTest`: most, kódy `OP_*`, odemykání) | 20 | prošly |

Nové testy z těchto verzí: osobní slova a pořadí návrhů, doplnění diakritiky (`auto_accent`), učení z vybraných oprav, rozšířený seznam návrhů,
sloučení stylů při obnově ze zálohy, přesná podoba výchozích rozložení kláves ve všech režimech.
Testy neověřují vzhled na displeji ani gesta; ty zůstávají na ručním projití na telefonu.
