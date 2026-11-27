# fAZT! – rychlá soukromá swipe klávesnice

Offline klávesnice pro Android s psaním tahem prstu přes písmena, česko-anglickým slovníkem, makry, diktováním
a velmi podrobným přizpůsobením vzhledu. Logika je v Rustu (`crates/keyboard-core`), Kotlin je tenká slupka
(`InputMethodService`, Canvas, haptika). Žádný internet, žádná reklama, žádná telemetrie.
Oprávnění: `VIBRATE` a `RECORD_AUDIO` (jen pro diktování).

**Stav:** verze **1.46** (`versionName` v `app/build.gradle.kts`). Funkce a vzhled jsou hotové a pokryté testy
(215 testů Rustu + 20 testů v JVM: JNI most, shoda kódů operací, postup odemykání). Na skutečném telefonu bylo zatím změřeno jen vykreslování v nastavení (viz [docs/audit-1.41.md](docs/audit-1.41.md)): build, testy
a logika jsou ověřené, ale vzhled na displeji, animace a gesta je potřeba projít ručně (viz [Známé limitace](#10-známé-limitace)).

Identifikátor balíčku je `cz.reallyfast`, zobrazovaný název je „fAZT!“ (vykřičník nemůže být v ID balíčku).
Pokud máš v telefonu starší build „RustBoard“ (`cz.rustboard`), odinstaluj ho – systém ho bere jako jinou aplikaci.

## Dokumentace

| Soubor | Pro koho a o čem |
|---|---|
| [docs/user-guide.md](docs/user-guide.md) | Uživatelský návod: psaní tahem, diktování, makra, schránka, vzhled |
| [docs/settings.md](docs/settings.md) | Seznam všech 94 nastavení: klíč, výchozí hodnota, rozsah, kde je v aplikaci |
| [docs/architecture.md](docs/architecture.md) | Architektura: Rust jádro ↔ JNI ↔ Kotlin, kam co patří, jak přidat nové nastavení |
| [docs/audit-1.41.md](docs/audit-1.41.md) | Audit, první měření výkonu na telefonu, nápady na zrychlení a zlepšení |
| [docs/privacy.md](docs/privacy.md) | Zásady ochrany soukromí (podklad pro Google Play) |
| [CHANGELOG.md](CHANGELOG.md) | Historie verzí, jedna věta za verzi |
| [CLAUDE.md](CLAUDE.md) | Pravidla projektu pro vývoj (styl kódu, testy, commity) |

## 1. Otevření v Android Studiu

1. Android Studio → **File > Open** → vyber tuto složku (root projektu).
2. Počkej na Gradle sync (Gradle 8.14.3, AGP 8.11.0, Kotlin 2.1.20, compileSdk 35, JDK 17+).
3. Nic dalšího není potřeba: přeložené Rust knihovny jsou v `app/src/main/jniLibs/`
   a slovník v `app/src/main/assets/dictionary.bin`.

Pokud je na PATH `cargo` + `cargo-ndk` a v SDK je NDK, Gradle před buildem Rust jádro sám přeloží
znovu (jen když se změnil Rust kód) a přepíše `jniLibs/*/libkeyboard_core.so`. Vypnutí: do `gradle.properties`
přidej `reallyfast.buildRust=false`. Přeložené `.so` jsou součástí repozitáře, takže po změně Rustu patří
do stejného commitu jako zdrojáky.

## 2. Sestavení APK

**Build > Build App Bundle(s) / APK(s) > Build APK(s)**

Výsledek: `app/build/outputs/apk/debug/app-debug.apk` (cca 17 MB).
Z příkazové řádky: `gradlew.bat :app:assembleDebug` (release: `:app:assembleRelease`; podepisuje se klíčem z `release.keystore`, `release.storePassword`, `release.keyAlias`, `release.keyPassword` v `local.properties`; bez nich release selže, nepodepisuje se debug klíčem).

## 3. Instalace do telefonu

1. Připoj telefon kabelem a přetáhni `app-debug.apk` do telefonu (např. do složky Download).
2. V telefonu otevři soubor v aplikaci Moje soubory a potvrď instalaci
   (při prvním použití povol „Instalovat neznámé aplikace“ pro Moje soubory).
   Alternativa: `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

## 4. Povolení klávesnice

Nejrychleji: otevři aplikaci **fAZT!** → *1. Povolit klávesnici v systému* → *2. Vybrat fAZT! jako aktivní*.
Nahoře je velké barevné pole se stavem: zelená ✓ = aktivní klávesnice, oranžový ! = povolená, ale nevybraná,
červený ✕ = zatím nepovolená.

Ručně: **Nastavení > Systém > Jazyky > Klávesnice na obrazovce** (na One UI:
**Nastavení > Obecná správa > Seznam klávesnic a výchozí**) → zapni fAZT! → nastav jako výchozí.
Android zobrazí standardní varování pro klávesnice třetích stran; fAZT! nemá oprávnění k internetu,
takže nic odeslat nemůže.

## 5. Co klávesnice umí

| Oblast | Funkce |
|---|---|
| Tap | CZ QWERTZ / EN QWERTY, Shift (tap = jedno písmeno, double tap = Caps Lock), dlouhý stisk = diakritika (a→á, e→é ě, u→ú ů …), zrychlující se backspace, psaní dvěma palci |
| Swipe | 64 bodů, beam search (šířka 50) přes trie, skóre 0.6 geometrie + 0.25 jazyk + 0.15 osobní frekvence, 3 návrhy, diakritika se doplní sama, citlivost 0.5–1.5 |
| Návrhy | doplňování slova, oprava překlepů (edit distance ≤ 2, nikdy automaticky), doplnění diakritiky, predikce dalšího slova z bigramů a trigramů |
| Učení | osobní slovník v SQLite (počet + čas použití), osobní bigramy a trigramy, zapomínání, vypínač; samostatná obrazovka Osobní slovník: ruční přidání slova, hledání, mazání, export/import JSON |
| Učení z oprav | když po swipe vybereš jinou alternativu nebo slovo hned smažeš, klávesnice si to zapamatuje a chybné slovo příště nabídne níž |
| Přizpůsobení swipe | z potvrzených tahů se učí, kudy prstem skutečně jezdíš, a posouvá si středy kláves (lze vypnout a smazat) |
| Gesta | tah vlevo z ⌫ = smazat slovo, tah po mezerníku = kurzor (rychlý švih = o slovo), švih nahoru = Caps, švih dolů = schovat, pinch = výška; vše vypínatelné |
| Jazyk | dlouhý stisk mezerníku: Čeština → English → Čeština · English |
| Rychlé přidání slova | tlačítko + vlevo v liště návrhů přidá rozepsané nebo poslední slovo do osobního slovníku |
| Makra a zkratky | tlačítko makra (maskot) otevře seznam vlastních textových bloků; makro se zkratkou se vloží i napsáním zkratky a mezery (`mfg` → „S pozdravem“); v textu lze použít `{datum}` a `{cas}`; makro lze přiřadit i přímo klávese (na klik nebo na podržení) a každé má vlastní obrázek |
| Diktování | podržení Enteru spustí diktování; po každé větě se pozastaví a pokračuje se ▶; křížek ho ukončí; vlastní hlasové povely (smazat slovo, nový řádek, vložit text …); postupně zkouší rozpoznávání v zařízení, službu aplikace Google a výchozí službu systému |
| Rychlá nabídka | podržení `?123`: emoji, schránka, nastavení klávesnice |
| Schránka | historie 30 položek, automatické mazání (výchozí 60 min), připnutí, smazání všeho, hesla a kódy se neukládají |
| Emoji | nedávné, smajlíci, zvířata, jídlo, aktivity, symboly + hledání podle názvu (česky i anglicky) |
| Úprava kláves | v nastavení lze na libovolnou klávesu dát jiný znak, nabídku znaků pro dlouhý stisk nebo makro a prohodit ?123/Enter a Shift/⌫ (vždy s potvrzením Ano/Ne) |
| Záloha a obnova | jeden JSON s nastavením, osobním slovníkem, opravami, makry, hlasovými povely a přizpůsobením swipe |
| Záznam tahů | volitelný, ve výchozím stavu vypnutý; ukládá dráhy tahů s potvrzenými slovy pro ladění vah (nástroj `tune`) |
| Přístupnost | TalkBack (popis každé klávesy), S Pen (hover nic nestiskne), landscape, split screen |

### Vzhled a nastavení

Nastavení vzhledu má **tři úrovně**, aby nikdo nemusel přemýšlet víc, než chce:

1. **Základ** (záložka *Hlavní*, vždy vidět): hotové styly, **Moje styly 1–5**, čtyři barvy, rozměry, přepínače
   (nápovědy, stopa, bublina, stín, animace, obrys speciálních kláves), efekt při klepnutí (žádný / Neon / Glitch /
   Barvy / Duch) a animace po úspěšném tahu (žádná / Postupně / Vybarvení / Slovo).
2. **Pokročilé** (sbalená karta v *Hlavní*): pozice a velikost znaků a nápovědy, mezery, barvy detailů a číslic,
   tloušťky písma, obrys kláves a barvy efektu Barvy.
3. **Expert** (záložka *Waiting*): všechny druhé barvy (přechody kláves, znaků, pozadí, obrysu, stopy, efektu,
   písmen po tahu) a barvy podle toho, co dělá prst (po klepnutí, pod prstem, po přejetí) pro znaky i obrys.

Přechody a „druhé barvy“ umí v některých řádcích i **duhu**. Pod záložkami je pořád vidět výběr stylů 1–5, nad náhledem vlevo šipka *Zpět* (vrací změny vzhledu
krok za krokem) a vpravo *Uložit* (uloží celý vzhled do vybraného stylu); obě se rozsvítí až po změně. Dole je živý
náhled klávesnice ve skutečné velikosti.
Úplný seznam voleb je v [docs/settings.md](docs/settings.md).

## 6. Struktura

```
app/                               Android aplikace (Kotlin, bez AndroidX)
  src/main/java/cz/reallyfast/
    KeyboardService.kt             InputMethodService, InputConnection, haptika, diktování
    KeyboardView.kt                vykreslení kláves (Canvas), dotyk, efekty, TalkBack
    Panels.kt                      lišta návrhů, emoji panel, panel schránky
    SettingsActivity.kt            hlavní obrazovka: aktivace, rozcestník, schránka, záloha
    Appearance.kt                  Vzhled a rozložení (záložky Hlavní / Waiting, živý náhled, editor kláves)
    SettingsScreen.kt, Ui.kt       společné stavební prvky obrazovek (řádky, výběr barvy, dialogy)
    Typing.kt, Voice.kt, Manage.kt obrazovky Psaní, Hlas, Osobní slovník, Makra
    RustBridge.kt                  JNI wrapper (kódy operací OP_*)
    Settings.kt                    pohled na nastavení + barvy tématu (Theme)
    Bubble.kt, ColorPlaneView.kt, ToggleView.kt   malé vlastní prvky
  src/main/res/                    texty (values = česky, values-en = anglicky), ikony, obrázky
  src/main/jniLibs/                přeložené libkeyboard_core.so (arm64-v8a)
  src/main/assets/dictionary.bin   slovník (FST)
  src/test/.../RustBridgeTest.kt   JVM test JNI mostu
crates/keyboard-core/              Rust jádro
  src/layout.rs  swipe.rs  dictionary.rs  predict.rs  settings.rs  gestures.rs  storage.rs
      learning.rs  clipboard.rs  emoji.rs  charmap.rs  macros.rs  voice.rs  backup.rs  jni.rs  lib.rs
  src/bin/build_dict.rs            sestavení dictionary.bin z TSV
  src/bin/tune.rs                  offline ladění vah swipe nad záznamem tahů
  benches/core.rs                  benchmarky
dictionaries/                      zdrojová data slovníku (cs_CZ.dic, cs_CZ.aff, czech_freq_15k.txt)
branding/                          zdroj ikony, zdroj loga, obrázek pro Google Play
scripts/download-dictionary.py     stažení korpusů + sestavení slovníku
scripts/ci.sh, scripts/ci.ps1      cargo test (CI)
.github/workflows/ci.yml           CI: testy Rustu
docs/                              dokumentace (viz výše)
```

Závislosti Rustu: `serde`, `serde_json`, `rusqlite` (bundled SQLite), `fst`. JNI je psané ručně
(bez crate `jni`), `unsafe` je jen v `src/jni.rs`. Podrobnosti v [docs/architecture.md](docs/architecture.md).

## 7. Slovník

`python scripts/download-dictionary.py` (potřebuje Python 3 a Rust):

| Zdroj | Použití | Licence |
|---|---|---|
| Hunspell `cs_CZ` (LibreOffice dictionaries) – `dictionaries/cs_CZ.dic/.aff` | správnost: expanze všech tvarů | GPL-2.0 |
| Frekvenční seznam lemmat ČNK – `dictionaries/czech_freq_15k.txt` (15 000 lemmat podle pořadí) | pořadí častosti | CC BY 4.0 |
| Leipzig Corpora `ces_news_2020_300K` | frekvence českých tvarů + bigramy | CC BY 4.0 |
| Leipzig Corpora `eng_news_2020_300K` | anglická slova, frekvence + bigramy | CC BY 4.0 |

Výsledek: **1 979 138 českých tvarů** a **58 100 anglických slov**, 9,3 MB včetně bigramů a trigramů (cs 47 749,
en 113 443). Slova bez frekvence mají nízkou výchozí frekvenci. Slovník se načítá na pozadí; tap psaní funguje okamžitě.
Kvůli licenci Hunspell slovníku je jádro vedeno jako `GPL-2.0-or-later`.

Poznámky k odchylkám od zadání:
- Dodaný frekvenční seznam má 15 000 lemmat bez počtů (ne 50 000 s frekvencí), proto frekvence tvarů
  a bigramy doplňuje český korpus z Leipzig Corpora (stejný zdroj a licence jako u angličtiny).
- Anglický seznam z korpusu 300K má 58 100 slov s výskytem ≥ 2 (požadavek ≥ 50 000 splněn).

## 8. Testy a benchmarky

```
scripts/ci.sh                      # cargo test (215 testů + testy bin a benchů)
scripts/ci.sh --bench --jni        # + benchmarky + JVM test JNI mostu
gradlew.bat :app:testDebugUnitTest # 20 testů (JNI most načtený do JVM, shoda OP_* kódů Rust ↔ Kotlin, odemykání)
```

Rychlá kontrola před commitem (z kořene repozitáře):

```
cd crates/keyboard-core && cargo test --lib
cd ../.. && ./gradlew.bat :app:testDebugUnitTest :app:assembleDebug --console=plain
```

Benchmark `cargo bench` nad reálným slovníkem. **Měřeno na vývojovém PC (x86-64) při vydání Fáze 1, ne na telefonu** –
na S25 Ultra čekej několikanásobně vyšší časy, stále s velkou rezervou proti limitům:

| Měření | Výsledek (PC) | Limit ze zadání |
|---|---|---|
| Načtení slovníku (9,3 MB) | cca 4 ms | neblokuje UI (běží na pozadí) |
| Dictionary lookup | 72 ns / dotaz | – |
| Swipe decode, čeština | průměr 0,66 ms, nejhorší 1,00 ms | 30 ms |
| Swipe decode, cs+en | průměr 1,21 ms, nejhorší 2,00 ms | 30 ms |
| Návrhy při psaní (doplnění + opravy) | průměr 0,43 ms, nejhorší 2,14 ms | 50 ms |
| Predikce dalšího slova | průměr 0,02 ms | 50 ms |
| Přesnost swipe, 600 častých slov, simulovaný tah se šumem | 1. místo 579–581, top 3 600/600 | – |

Přesnost je měřená na simulovaných tazích (ideální dráha + šum), ne na skutečném psaní prstem.
Vykreslení snímku, odezva tapu, start klávesnice a paměť **nebyly na zařízení změřeny**; kód je na ně navržen
(žádné alokace v `onDraw`, synchronní JNI, líné načtení slovníku).

### Záloha a obnova

Hlavní obrazovka → **Záloha a ladění** → *Zálohovat do souboru* uloží jeden JSON (`fazt-zaloha.json`):
nastavení (včetně uložených stylů 1–5), osobní slovník (slova, bigramy, trigramy), opravy, makra, hlasové povely,
přizpůsobení swipe a záznam tahů. Historie schránky a nedávná emoji se do zálohy **nedávají** (soukromí).

*Obnovit ze souboru* se nejdřív zeptá a pak: nastavení **přepíše**, slovník, opravy a makra **sloučí**
(u slov a oprav platí vyšší počet, existující makra se nezdvojují) a přizpůsobení swipe nahradí, pokud je
v souboru. Záloha ze starší verze, která ještě neměla styly 1–5, nastavení obnoví a **styly v telefonu nechá,
jak jsou**. Opakované obnovení stejného souboru už nic nezmění. Neplatný soubor (nebo soubor jiné aplikace)
se odmítne a nic se nezmění. Samostatný export/import jen slovníku zůstává na obrazovce Osobní slovník.

### Záznam tahů a ladění vah swipe

Přepínač **Záznam tahů pro ladění** (ve výchozím stavu vypnutý) ukládá do databáze v telefonu syrové dráhy
tahů, geometrii kláves a výsledek: potvrzený první návrh, vybraná alternativa nebo slovo hned smazané.
Nezaznamenává se v polích pro hesla ani tam, kde je učení zakázané; drží se nejvýše 1000 posledních tahů.
Tlačítko *Exportovat záznam tahů* ho uloží do `fazt-tahy.json`, *Smazat záznam tahů* ho vymaže.

Nástroj `tune` tahy přehraje přes decoder, změří přesnost s výchozími vahami, vypíše chyby a zkusí mřížku
vah (geometrie 0,45–0,75, jazyk = 0,85 − geometrie, osobní 0,15, strmost 2,0–4,0). Pod 30 použitelných tahů
jen vypíše, že vzorek je příliš malý na změnu vah. Spuštění z `crates/keyboard-core`:

```
cargo run --release --bin tune -- ../../app/src/main/assets/dictionary.bin <soubor>
```

## 9. Soukromí a bezpečnost

Podrobně v [docs/privacy.md](docs/privacy.md). Ve zkratce:

- Manifest obsahuje `VIBRATE` a `RECORD_AUDIO`, **ne** `INTERNET`; žádná síťová knihovna v projektu není.
- Diktování používá systémovou službu rozpoznávání řeči (na Androidu 12+ přednostně rozpoznávání v zařízení).
  Zvuk zpracovává tato služba systému podle svého nastavení – pokud v telefonu není offline model češtiny,
  může ho služba (Google / Samsung) posílat na své servery. Mikrofon se zapíná jen při diktování;
  diktování jde vypnout v nastavení a oprávnění k mikrofonu odebrat v systému.
- Data jsou v privátním adresáři aplikace (`settings.json`, `reallyfast.db`), zálohy a přenos do jiného
  zařízení systémem jsou vypnuté, při odinstalaci se vše smaže.
- Osobní slovník **není šifrován na úrovni aplikace**: SQLCipher vyžaduje křížovou kompilaci OpenSSL
  a ChaCha20Poly1305 další závislost mimo povolený seznam. Spoléhá se na sandbox Androidu a systémové
  šifrování úložiště (zdůvodnění je i v `storage.rs`).
- V polích pro hesla a v polích s příznakem „no personalized learning“ se nic neučí a swipe je vypnutý.

## 10. Známé limitace

- **Netestováno na reálném zařízení** – včetně vzhledu, animací, bublin, stínů, efektů a gest. Ověřeno: build APK,
  Rust testy, JNI most v JVM. Chování v aplikacích a vzhled na displeji je potřeba vyzkoušet na telefonu.
- Diktování závisí na službě rozpoznávání řeči v telefonu (kvalita češtiny, offline dostupnost); v polích pro hesla je vypnuté.
- Švih nahoru/dolů (Caps / schovat) musí být rychlý, dlouhý a téměř svislý; výjimečně se může splést
  se swipe psaním svislého slova. Lze vypnout.
- Dlouhý stisk mezerníku přepíná jazyk až po delší prodlevě (min. 500 ms), aby nekolidoval s tahem kurzoru.
- Swipe nezná slova mimo slovník, dokud se je nenaučí tapem; nepíše víceslovné výrazy („na shledanou“).
- Predikce se dívá nejvýš dvě slova zpět (trigramy); trigramy jsou jen z 300 tisíc vět korpusu, takže řidší spojení nezná.
- Přizpůsobení swipe neukládá surové tahy – jen průměrný posun pro každé písmeno. Váhy skóre (0,6 / 0,25 / 0,15) se samy nemění.
- Seznam maker na klávesnici ukazuje nejvýš 6 maker (ostatní jdou vložit zkratkou); na klávesy lze přiřadit nejvýš 16 maker.
- Automatická velká písmena se řídí příznaky textového pole; aplikace, které je nenastaví, je nedostanou.
- Historie schránky se plní, jen když je fAZT! aktivní klávesnice (omezení Androidu 10+).
- Emoji: cca 300 kusů, bez variant barvy pleti.
- Split/floating klávesnice pro foldables není; rozložení se jen přizpůsobí šířce.
- Obrázek makra má jen 4 varianty a maskot má malé rozlišení (107×88), na velkých klávesách může být měkčí.
- Gradient znaků a výška přechodů je přibližná (počítá se z velikosti písma, ne z přesné výšky glyfu).
- targetSdk 35. Pro Google Play je nutný vlastní release klíč (viz sestavení) a zásady ochrany soukromí.

## 11. Co dál

- Projít všechno na skutečném telefonu (vzhled, animace, efekty, gesta, diktování) a doladit podle dojmu.
- Měření na S25 Ultra (frame time, paměť) a doladění vah swipe podle reálných tahů.
- Větší korpus pro trigramy, případně malý neuronový jazykový model.
- Šifrování osobního slovníku klíčem z Android Keystore.
- Jednoruční režim, plovoucí klávesnice, více jazyků.
- Pro Google Play: vytvořit release keystore (mimo repozitář) a zveřejnit [zásady ochrany soukromí](docs/privacy.md).
- Výkon: postup a pořadí větších úprav je v [docs/plan-vykon.md](docs/plan-vykon.md).
