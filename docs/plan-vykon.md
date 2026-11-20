# Plán: výkon a odlehčení (další krok)

Stav k verzi 1.23.3. Hotová je „bezpečná dávka“ (jedna transakce v `learn()`, kompaktní JSON nastavení, úklid `wake`).
Zbylé body jsou větší zásahy. **Žádný z nich se nemá dělat naslepo**: nejdřív změřit na telefonu, pak měnit.

## Výsledek měření (v1.23.3, debug build, jeden telefon, běžné osobní slovníky)

Časování je v debug buildu (`Perf.kt`, logcat značka `fazt-perf`, po 8 vzorcích medián / p90 / max; v release vypnuté).

| Co | Medián / max | Rozpočet |
|---|---|---|
| `suggest` | 0,1–1,1 ms / 1,9 ms | 8 ms |
| `swipe` | 5,4–7,3 ms / 9,8 ms | 30 ms |
| `learn` | 4 ms / 8,3 ms | – |
| `onDraw` | 0,7–1,9 ms / 2,7 ms | 16 ms |
| `reloadLayout` | 1,4–2,6 ms / 4,4 ms (49 volání za test) | 50 ms |
| `applySettings` | 0,1 ms (jednou 4 ms) | 50 ms |
| `setSettings` celkem | 14 ms / 18 ms (z toho 6,5 ms v Rustu) | 50 ms |

Závěr: vše je pod rozpočtem. Kroky 2 a 3 (index slov, méně dotazů do editoru) se **nedělají**, měření je nepotřebuje.
Krok 1 je jen úklid (ušetří asi 2,5 ms na změnu a zbytečné přestavby), ne oprava problému. Neověřeno: velký osobní slovník
(5 000 a 20 000 slov), release build a plynulost na displeji okem.

## 0. Měření (hotovo, viz výše)

Cíl: zjistit, co je na skutečném telefonu pomalé, ne co vypadá pomalé v kódu.

- Zapnout na telefonu „Profil GPU vykreslování“ (vývojářské možnosti) a psát i tahat po klávesnici: sleduje se, jestli sloupce překračují čáru 16 ms.
- Přidat do debug buildu časování (`SystemClock.elapsedRealtimeNanos`) kolem: `RustBridge.suggest`, `RustBridge.swipe`, `RustBridge.learn`, `KeyboardView.reloadLayout`, `applySettings`. Výpis do logcatu jen v debug buildu.
- Zkusit se zaplněným osobním slovníkem (např. 5 000 a 20 000 slov; skript, který je naimportuje přes zálohu).
- Rozhodnutí: krok se dělá, jen když naměřená hodnota přesáhne rozpočet (psaní: návrhy do 8 ms, swipe do 30 ms, změna nastavení do 50 ms).

## 1. Oddělit barvy od geometrie (největší přínos pro nastavení)

Dnes: `KeyboardService.applySettings()` při každé změně volá `reloadLayout()`, `showKeyboard()` a ruší panely emoji a schránky. Změna barvy tak přestavuje geometrii kláves.

Kroky:
1. V `Settings` zavést dvě sady klíčů: **geometrie** (`key_height_dp`, `key_gap_dp`, `row_gap_dp`, `language`, `show_number_row`, polohy, makra, …) a **vzhled** (barvy, efekty, obrys). Seznam vedle `STYLE_KEYS` v `settings.rs`, aby sedělo Rust ↔ Kotlin.
2. `applySettings()` porovná poslední použité hodnoty geometrie s novými. Beze změny geometrie jen `theme = Theme.from(...)`, `applyAppearance()` a `invalidate()`, bez `reloadLayout()` a bez rušení panelů.
3. `KeyboardView.applyAppearance()` rozdělit: přepočet barev a shaderů zvlášť, `updateMetrics()` jen při změně velikosti.
4. Totéž pro náhled v `Appearance.onSettingsChanged()` (`updatePreview()`).

Riziko: zapomenutý klíč ve skupině geometrie = klávesnice se po změně nepřekreslí správně. Pojistka: Rust test, že každý klíč nastavení je v právě jedné skupině.
Ověření: tah posuvníku barvy v náhledu nevolá `reloadLayout()` (čítač v debug buildu).

## 2. Index osobních slov podle prefixu

Dnes: `predict.rs` (řádek ~346, `suggest_composing`) a `swipe.rs` (~576, `decode`) procházejí celé osobní slovníky při každé klávese / tahu.

Kroky (až když měření ze kroku 0 ukáže zpomalení při tisících slov):
1. V `UserDict` držet `BTreeMap<Vec<u8>, Vec<usize>>` (složený prefix → indexy slov), obnovovat při `learn`, `forget`, `import`.
2. `suggest_composing` hledá přes rozsah prefixu místo celé iterace.
3. Pro swipe: předfiltrovat osobní slova podle první a poslední písmene (stejně jako u vestavěného slovníku).
4. Testy: stejné výsledky návrhů a swipe jako před změnou na pevné sadě slov (porovnávací test), plus bench s 20 000 slov.

Riziko: nekonzistence indexu po importu / mazání. Pojistka: test, že po každé operaci index odpovídá přestavbě od nuly.

## 3. Méně dotazů do editoru (Binder)

Dnes 21 volání `getTextBeforeCursor` v `KeyboardService.kt` (návrhy, potvrzení slova, shift, mezera, backspace).

Kroky:
1. Zavést jednu funkci `textBefore(n)` s malou cache: platí pro stejný `(selStart, selEnd, composing)` mezi dvěma událostmi (zneplatnit v `onUpdateSelection`, `onStartInputView`, po vlastním `commitText`).
2. Postupně nahradit přímá volání, po jednom místě za commit (`typeText`, `commitComposing`, `updateShift`, `space`, `backspace`).
3. Backspace: z `reattachWord` odstranit druhé volání (`getExtractedText`), kde to jde, nebo použít jediný dotaz.

Riziko: zastaralý text ve cache = špatné velké písmeno nebo špatná oprava. Nejnebezpečnější část celého plánu, dělat poslední a po malých krocích, každý krok vyzkoušet v běžných aplikacích (zprávy, prohlížeč, poznámky).

## 4. Učení a start mimo hlavní vlákno (volitelné)

- `learn()` je teď jedna transakce; další zrychlení jen s měřením. Přesun na vlákno vyžaduje frontu zápisů a pořadí vůči `suggest` (jinak nové slovo chvíli není v návrzích).
- `Engine::with_storage` (start) načítá nastavení, slova, makra: měřit; případně načítat slova líně po prvním zobrazení klávesnice.

## Pořadí

0 → 1 → (2 podle měření) → 3 → (4 podle měření). Každý krok zvlášť commit, po každém `cargo test --lib`, JVM testy, build a zkouška na telefonu.
