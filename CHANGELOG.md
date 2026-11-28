# Changelog

Jedna věta za verzi, nejnovější nahoře. Čísla verzí odpovídají commitům (`vX.Y: …`).

## 1.54 – 2026-11-28
Nový efekt po klepnutí Plazma: shader (AGSL, Android 13+) zvlněné dvoubarevné kruhové světlo jen přes stisknutou klávesu; na starším systému se chová jako Neon.

## 1.53 – 2026-11-27
Uložit ve vzhledu: text povyskočí a přes tlačítko projedou zleva doprava dva křivé pruhy (zelený a žlutý); R losuje každou druhou barvu i směr přechodu zvlášť (od jedné úpravy po všechny); při nahrávání nového povelu je nad lištou návrhů tabulka „Nahraj max. 4 slova!“ a na výběr jsou i klasické povely (smazat slovo, nový řádek, Enter, ukončit diktování).

## 1.52 – 2026-11-27
Podržení ?123 už nenabízí emoji (jen schránku a nastavení; emoji zůstaly na podržení tlačítka maker); nový hlasový povel z podržení mikrofonu se ukládá i při delším spouštěči (ořízne se na 4 slova) a seznam povelů se při návratu na obrazovku obnoví.

## 1.51 – 2026-11-27
Tlačítko R losuje navíc náhodný počet skupin úprav (od žádné po všechny): tvary, druhé barvy se směrem přechodu (klávesy, znaky, pozadí, čísla, obrys), barvy po klepnutí a po tahu; nevylosované se vrátí na výchozí; Řez a Slide přidány do přehledu nastavení.

## 1.50 – 2026-11-27
Druhá barva klávesy po klepnutí je v Animaci po kliknutí III a řídí se efektem (Neon, Výbuch, Řez a Slide střídají, Glitch losuje, Duch střídá barvu dozvuků); Barvy mají další tři barvy (4–6); Výbuch rozmetá i celou klávesu na větší kusy; nahoře vedle Obnovit je tlačítko R s úplně náhodným stylem.

## 1.49 – 2026-11-27
Po úpravě polohy / výšky řádků se náhled vrací do zkušebního režimu; nový posuvník Výška lišty návrhů (`bar_height_dp`); podržení ikony schránky otevře nabídku připnutých položek (až 100, ukládají se jen v telefonu a jdou do zálohy); podržení mikrofonu nadiktuje spouštěč nového hlasového povelu a nabídne „Použít makro“ nebo „Poslat ze schránky“.

## 1.48 – 2026-11-27
Nová obrazovka Záloha a obnovení (export a import s vysvětlením, že data nikam neodcházejí); jiskry u „Nový povel“ v Hlasu vybuchují přesně na plusu; podržený návrh otevírá své dvě další slova přímo nad sebou, každý slot má vlastní dvojici (šest slov celkem, Rust vrací o 6 slov víc), výběr z nich lehce zabrní.

## 1.47.6 – 2026-11-27
Po přidání slova v osobním slovníku písmena tlačítka Přidat odletí (P nahoru, ř dolů, i dolů, d nahoru, a dolů, t doprava) a tlačítko se vrátí; když je klávesnice aktivní, obě ozubená kola na úvodní stránce se při každém pootočení točí nahodilým směrem (stejně nebo každé jinak).

## 1.47.5 – 2026-11-27
Osobní slovník má zpět textové pole před Přidat (jiskry roztahovaly obal tlačítka); stav klávesnice je samostatný symbol bez kolečka a 1,5× větší (✓, !, ✕), znaky kroků 1 a 2 jsou o třetinu menší.

## 1.47.4 – 2026-11-27
Skutečná příčina slepeného řádku hlavičky: vlna kolem tlačítka Uložit roztahovala obal na celou šířku; má teď vlastní míru nula, takže Obnovit a Uložit leží vpravo ke kraji.

## 1.47.3 – 2026-11-27
Revize kódu: klávesnice po odpojení ruší zbylé časovače, dva bezpečnostní přepisy `!!` na lokální hodnoty; release APK sestaven a podepsán.

## 1.47.2 – 2026-11-27
Hlavička Vzhledu je jeden vodorovný řádek: Zpět vlevo, Test, Obnovit a Uložit vpravo ke kraji obrazovky, bez překrývání tlačítek.

## 1.47.1 – 2026-11-27
Hlavička Vzhledu má pevnou šířku, takže Obnovit a Uložit leží vpravo a ne přes sebe; Hlas má dva samostatné přepínače: diktování podržením Enteru a diktování tlačítkem vedle schránky (volba „Jak spouštět“ zmizela).

## 1.47 – 2026-11-27
Osobní slovník má kompaktní horní pole a po přidání slova vystřelí kolem tlačítka žlutozelená vlna a jiskry, velké ozubené kolo je schované za panelem aktivace a malé je posunuté, Obnovit a Uložit jsou zpět vpravo v hlavičce, kulička přepínače má tenký obrys, čtvercové tlačítko posuvníku se při tažení občas glitchne do žlutooranžové hvězdičky s tyrkysovou a červenou kopií obrysu.

## 1.46 – 2026-11-27
Tlačítko emoji je pryč: emoji se otevírají podržením tlačítka maker, které je výchozně vpravo vedle mezerníku (a jde přesunout nebo skrýt), nové makro má vlnu textu, výběr barvy bez OK vrací původní barvu, obnova profilu má nový obrázek a po potvrzení se otočí o 360°, přepínače mají nový vzhled (obrys kapsle, kulička v barvě akcentu, dvojí glitch na čtvereček), úvodní stránka má u kroků aktivace zaškrtávátka a dvě pomalu se otáčející ozubená kola, Uložit se zvětší a vystřelí zlutozelenou vlnu.

## 1.45 – 2026-11-27
Profil (styl 1–5) obsahuje i rozložení (posuny řad, poloha tlačítek emoji a maker) a tlačítko obnovy ho vrací celé, ze Swipe I zmizel přepínač průchodu a barva kláves průchodu (je to barva stopy), animace Text po tahu přistává nad celým náhledem, nové slovo v osobním slovníku zabrní a tlačítko Přidat se zvětší, zezelená a vybouchne, Hlas má volbu spouštění (podržení Enteru / tlačítko v liště / obojí) a nový povel má animaci křížku, šipky posuvníků po klepnutí zablikají zeleně nebo žlutě, přepínač je o třetinu větší a při přepnutí se zploští, dojede a nafoukne na 125 %.

## 1.44 – 2026-11-27
APK jen pro arm64 (z 13,3 na 9,9 MB), návrhy se při psaní počítají po 35 ms pauze, rychlejší sběr kandidátů na návrhy (`Pool` s hash mapou) i pro velké osobní slovníky, audit doplněn o rozhodnutí k `panic = abort` a `mmap`.

## 1.43 – 2026-11-27
Úprava výšky řádků (Rozložení a písmo III: ztmavená klávesnice bez písmen, šipky u řad, tah nahoru a dolů s mezemi pro každou řadu, nové nastavení `row_offsets` platné i pro swipe), šipka zpět podle nového obrázku, úroveň I se v kartách vybarvuje do zelena.

## 1.42 – 2026-11-27
Nové duté šipky (zpět, otevření obrazovky, konce posuvníků, karty vzhledu) s výplní zleva doprava, jiskrami a vystřelením, čtvercové jezdce posuvníků, tlačítko obnovy profilu ve Vzhledu, pulzující zkušební pole po dokončení nastavení, Waiting jen s obrysem znaku (nová barva `char_outline_color`) a začátky přechodů, obrys speciálních kláves pevně v akcentu, tloušťka čáry stopy podle nastavení, pomalejší a zvýrazněný banner uloženého slova.

## 1.41 – 2026-11-27
Na hlavní obrazovce bliká obrys dalšího kroku nastavení (povolit klávesnici, pak ji vybrat) a po dokončení zhasne, podržení návrhu v liště ukáže další dvě slova, obrys znaku ve Waiting drží průhlednost i barvu obrysu, zapsán audit a první měření výkonu (docs/audit-1.41.md).

## 1.40 – 2026-11-27
Obnova ze zálohy sloučí uložené styly: tvoje zůstanou, styl ze zálohy se uloží na své číslo (je-li volné) nebo na první volný slot, a když je všech 5 obsazených, zeptá se, který slot nahradit; testovací skript ukládá log spolehlivě po Ctrl+C.

## 1.39 – 2026-11-27
Mikrofon v liště vedle schránky, lišta variant zůstává vidět při výběru své barvy, výběr písma, animace a efektu už po zavření neposune obsah, mezerník doplní diakritiku neznámému slovu (kdyz → když, nový přepínač v Psaní), klepnutí na slovo nabídne podobná slova (i uprostřed slova) a vybraná oprava se příště nabídne dřív, test výchozího rozložení kláves.

## 1.38 – 2026-11-27
Po odeslání zprávy se rozepsané slovo už nevrací do prázdného pole (Messenger), bublina zůstává vidět při výběru její barvy, ukázka tahu běží dokola po řadě od a do l i s barvami průchodu, druhá barva písmen po tahu se střídá po písmenech, odstraněna druhá barva obrysu čísel a nadbytečné druhé barvy ze Swipe II a Animace po kliknutí III, Animace po kliknutí II má druhou barvu klávesy a znaku, barva nápovědy je jen v Barvách II.

## 1.37.1 – 2026-11-26
Osobní slova se v Rustu kódují jen jednou (rychlejší návrhy a swipe), úklid mrtvého kódu a 38 nepoužitých řetězců, pomocné funkce pro text před kurzorem, bez alokace v kreslení hlasového režimu, ladicí zápisy `fAZT` do logcatu a testovací slovník (`testdata/`).

## 1.37 – 2026-11-26
Vzhled a Rozložení má vždy vybraný profil (výchozí první), zkušební režim se obnoví po návratu z plochy a platí i ve Waiting; lišta návrhů nabízí i podobná slova a slova z vlastního slovníku častěji; návrh nezdvojí slovo, když editor po zalomení řádku ztratí rozepsané slovo.

## 1.36.1 – 2026-11-25
Nový postup odemykání poslední kategorie; po dokončení se dotčená nastavení vrátí do výchozího stavu.

## 1.36 – 2026-11-25
Karty kategorií: zavřené mají přechod zleva doprava šedá–modrá (střídavě), otevrené podle úrovně shora dolů (I žlutošedá, II oranžovošedá, III červenošedá); barvy se dělí na první (I), druhé (II) a barvy číslic (III); plus v liště se při každém přidání/úpravě slova protočí o 180° tam a zpět na obě strany; poloha znaku a nápovědy je na sobě i na velikostech nezávislá; šipka kategorie se při zavření nejdřív otočí zpět a pak se zmenší a zabarví.

## 1.35.1 – 2026-11-25
Karta Rozložení se podle úrovně zespodu nahoru vybarví: I zelenošedě, II oranžovošedě, III červenošedě.

## 1.35 – 2026-11-25
Mezera mezi řadami (až 0) zmenšuje i celkovou výšku klávesnice; velikost znaků a napovědy se mění jen svým nastavením (`key_text_percent`, `hint_percent`) a nezávisí na výšce a šířce kláves.

## 1.34.1 – 2026-11-25
Tloušťka a barva obrysu v ukázce blikají jen obrysem; při probíhajícím textu v mezerníku (uložené slovo) je základní popisek mezerníku schovaný a po skončení se krátce objeví.

## 1.34 – 2026-11-25
Tloušťka písma kláves a číslic skutečně funguje (rodiny thin–black + dokreslení pro tlustší, číslice mají vlastní tloušťku); Řez rozřízne i nápovědu; nové nastavení `passed_key_color` (barva kláves průchodu); Enter se při klepnutí protočí o 360°; Shift se plynule zvětší a zabarví do akcentu a zmenší zpět, klon znaku je pryč; smazané popisky u stopy, průchodu a bubliny; výběr barvy je čtverec s pruhem odstínu, hex kódem a tlačítkem OK a otevírá se jen klepnutím na kolečko barvy.

## 1.33 – 2026-11-25
Blikání v náhledu se přepočítává z aktuálních kláves (sedí i při vyšších klávesách); šířka klávesy 24–48 dp; tloušťka písma se u vlastního písma zatmí; druhá barva znaků a všechny přechody se kreslí bez měnících se shaderů; druhé barvy po klepnutí (znak, obrys) a neonu (Neon, Obvod, Duch) tvoří přechod uvnitř jedné klávesy; Ohnivá stopa má jen šířku, která mění velikost plamene; Otočení písmen mění barvu písmen podle barvy po úspěšném tahu (+ druhá barva); u Waiting naskakují tečky.

## 1.32 – 2026-11-25
Druhé barvy jdou zase shora dolů; ve Waiting se u každé (pozadí, klávesy, klávesy čísel, znaky, znaky čísel, obrysy, obrysy čísel) vybírá začátek přechodu (shora, zdola, zleva, zprava). Slide odjíždí v náhodném směru z 360°. Pod výběrem efektu Barvy jsou tři kolečka barev. Otočení písmen je mezi animacemi po úspěšném tahu (ne mezi stopami). Tlačítko Odeslat při diktování je víc vpravo a jen se zelenou šipkou. Zrušen nefunkční posuvník výšky klávesnice; mezeru mezi řadami jde stáhnout až na 0. Šipka otevřené kategorie se pomalu otočí o 45°, zvětší na 150 % a zabarví. Po přidání slova přes + slovo zabliká a text přes mezerník má barvu opačnou k barvě kláves.

## 1.31 – 2026-11-25
Ukázky v Nastavení blikají přesně to, čeho se týkají (oddělovač jen jako čára, jemný stín se zvětší na 200 % a zabliká, obrys speciálních kláves jen obrysem, barva pozadí jen pozadím); makro a smajlík mají skutečně speciální obrys. Smazat: znak se rozčtvrtí a čtvrtiny odletí na 2., 5., 8. a 11. hodinu. Enter: zelená výplň zleva doprava a zelený pulz kolem tlačítka. Shift je normálně menší a při velkých písmenech větší. Zrušeno samostatné písmo nápovědy (nápověda má písmo kláves, nastavení `hint_font` zaniklo).

## 1.30 – 2026-11-25
Styly 1–5 zpět jako čtverečky; náhled je v kategoriích 0–4 trvale zkušební (jde psát, ukázka se po 3 s znovu rozběhne), Test zůstal jen v kategorii 5; v Animacích a Swipe běží dokola ukázka slov FAZT/ahoj (2 % NEČUM) místo blikání; Slide má směr po 30°; Řez rozpůlí celou klávesu, půlky odletí a nová se zrodí; úspěšný tah má jen Neon / Výplň / Text; nový vzhled stopy „otočení písmen“ (90–180°); stopa a animace tahu se vybírají rozbalovacím seznamem; druhé barvy čísel (klávesy, znak, obrys); všechny druhé barvy jdou zleva doprava a v Waiting je mají všechny; Duch se kreslí jen na klepnuté klávese (žádné okolní).

## 1.29.2 – 2026-11-24
Výběr barvy má hex pole (jde z něj kopírovat i do něj vložit kód); při diktování je místo „Znovu mluvit“ tlačítko Odeslat (dokončí přepis a stiskne Enter); šířka samostatné klávesy je omezena na 24–48 dp a nikdy nepřesáhne šířku svého místa; posuvníky rozměrů (výška klávesnice a kláves, šířka, velikost písma) v náhledu neblikají; výběr písma ukáže bublinu, lištu variant i nápovědu v novém písmu.

## 1.29 – 2026-11-23
Přestavba obrazovky Vzhled: pevná hlavička (Zpět, Test, Uložit a posuvník stylů 1–5), náhled klávesnice vyjíždí plynule po rozbalení kategorie, kategorie mají tlačítka úrovní I–III, klepnutí na řádek ukáže nápovědu a v náhledu zabliká dotčená část v barvě akcentu (ostatní klávesy se ztlumí), ukázky animací píšou slova FAZT / ahoj (občas NEČUM); nové nastavení `hint_font` (písmo nápovědy), výběr písma ukazuje každé písmo samo sebou; tloušťka obrysu je dostupná v Barvách; zkušební tahy z Testu se nezapisují do záznamu tahů a při odchodu z obrazovky náhled plynule zajede.

## 1.28 – 2026-11-21
Výbuch je živější (jiskry létají do všech stran po náhodných, zakřivených drahách s náhodným časem a rychlostí); efekt stopy „Memorocht“ a efekt po klepnutí „Obličej“ jsou smazané (staré hodnoty se vrátí na Čáru a Neon); nová stopa „Oheň“ (hořící čára se žhavými stopami, které dohoří) a nová animace po tahu „Řez“ (každé písmeno slova se rozřízne a půlky odpadnou).

## 1.27 – 2026-11-20
Nový efekt stopy „Memorocht“ (za prstem se po dráze tahu objevují obrázky maker jedna za druhou, `trail_style`) a nový efekt po klepnutí „Obličej“ (písmeno se změní na obrázek makra a klávesa se pak rozsvítí).

## 1.26 – 2026-11-20
Úklid podle revize: jádro vrací id uloženého makra (obrázek se přiřadí přesně jemu), test hlídá shodu kódů operací Rust ↔ Kotlin, release bez klíče se nesestaví (žádné podepsání debug klíčem), zapnutý lint, CI testuje i Android (JVM testy, debug build, lint), animace Slovo používá předpočítanou tabulku písmen, opravené zastaralé komentáře a `targetApi`.

## 1.25 – 2026-11-20
Waiting se po správném zadání jen otevře (nic se nepřepisuje) a ukáže okno „Downgrade dokončen!“; řádky v Nastavení mají tenké čárové ikony; nepoužité styly 2–5 mají jednu barvu a základní efekty (nezměněné starší se nahradí); přepnutí stylu je okamžité a při neuložených změnách se zeptá, zda ho změnit bez uložení.

## 1.24.2 – 2026-11-20
Oprava: Waiting se po správném zadání opravdu otevře.

## 1.24.1 – 2026-11-20
Po správném zadání se Waiting hned otevře.

## 1.24 – 2026-11-20
Efekt při klepnutí se vybírá z rozbalovacího seznamu a Obvod a Výbuch jsou v něm na Hlavní záložce (z Waiting zmizely); v seznamu maker je před názvem vidět obrázek makra; Waiting se odemyká novým postupem.

## 1.23.3 – 2026-11-20
Měření výkonu jen v debug buildu (`Perf.kt`, logcat `fazt-perf`): návrhy, swipe, učení, změna nastavení, přestavba geometrie a kreslení. Výsledky v `docs/plan-vykon.md`; v release je vypnuté.

## 1.23.2 – 2026-11-20
Dvojklepnutí na napsané slovo v liště návrhů a uložení beze změny ho přidá do osobního slovníku a zavibruje.

## 1.23.1 – 2026-11-20
Učení slova zapisuje do databáze v jedné transakci; nastavení se ukládá jako kompaktní JSON; `KeyboardView` po odpojení ruší naplánované překreslení; aktualizovaná dokumentace (README, uživatelská příručka, architektura, soukromí) a nový plán výkonu `docs/plan-vykon.md`.

## 1.23 – 2026-11-19
Výběr stylu 1–5 je pod záložkami nahoře a při posouvání zůstává; nad náhledem vlevo šipka Zpět (vrací změny vzhledu krok za krokem) a vpravo Uložit (do vybraného stylu), obě se rozsvítí až po změně.

## 1.22.1 – 2026-11-19
Efekty po klepnutí (včetně Obvodu a Výbuchu) a barvy po klepnutí fungují i na mezerníku.

## 1.22 – 2026-11-19
Výběr stylu 1–5 je připnutý nad náhledem a vybraný styl je stále zvýrazněný (pamatuje se); tlačítko Yoda je pryč; chycená hlava vyjede celá, otočí se o 360° a zajede zpět; ve Waiting dva nové efekty po klepnutí (Obvod a Výbuch).

## 1.21.4 – 2026-11-19
targetSdk 35; release se minifikuje a zmenšuje se o nepoužité zdroje; release podpis z local.properties (bez něj varování a debug klíč); AndroidX vypnuto.

## 1.21.3 – 2026-11-19
Mazání schránky se vždy provede; posuvníky zapisují nastavení nejvýše každých 100 ms; doplněné anglické řetězce; varování, že záloha není šifrovaná; LICENSE a NOTICE; verze jádra sjednocena s aplikací; build hlasitě varuje, když se Rust nepřeložil.

## 1.21.1 – 2026-11-19
Prohození speciálních kláves (?123, Enter, Shift, ⌫) se vždy potvrzuje (Ano / Ne); obrázek makra na podržení je stejně velký jako nápověda.

## 1.21 – 2026-11-19
Obrázek patří makru a vybírá se při jeho tvorbě (pod zkratkou); nový maskot je i ikonou aplikace; animace loga vždy skončí na `logo1`.

## 1.20 – 2026-11-19
Všechny druhé barvy jsou ve Waiting (nově stopa, písmena po tahu, znak a obrys po klepnutí); obrys Shiftu, ?123 a ⌫ v barvě akcentu; makro na podržení se kreslí na pozici nápovědy; výrazné pole se stavem klávesnice.

## 1.19 – 2026-11-19
Nastavení rozdělené do tří úrovní (Základ, Pokročilé, Expert/Waiting); nový efekt Duch (dozvuk); Barvy a Slovo jsou ve výběru efektů.

## 1.18 – 2026-11-19
Obrys kláves (stálý i podle stavu prstu), druhá barva efektu, výběr obrázku makra, maskot na tlačítku maker, nové logo s animací, návštěvník nad celým náhledem.

## 1.17 – 2026-11-19
Náhodná velikost posunu kopie u Glitche, duha jako barva, uložené styly 1–5, tlačítko Yoda pro zkoušení, čitelný text nad číslicemi.

## 1.16 – 2026-11-16
Občasný návštěvník nad klávesnicí, logo v nastavení.

## 1.15.1 – 2026-11-16
Výraznější vibrace při uložení slova, animace Slovo vyskočí a problikne.

## 1.15 – 2026-11-16
Barva stopy, druhá barva znaků, rychlejší Postupně, jednodušší hlavní záložka.

## 1.14 – 2026-11-16
Volby bez animace, makro na klik nebo na podržení, druhý obličej, úklid varování.

## 1.13 – 2026-11-16
Oprava zaseknutí v režimu opravy slova, tloušťka písma, barvy znaku při tahu.

## 1.12 – 2026-11-15
Napojení na slovo při mazání, Glitch, makro na klávese, pozice znaku.

## 1.11 – 2026-11-15
Přepracovaný Vzhled a rozložení, barevné kolo, nové efekty a záložka Waiting.

## 1.8 – 2026-11-15
Název fAZT!, obrázek na klávese maker, pozastavené diktování, tlačítka v nastavení.

## 1.7 – 2026-11-15
Animace vybarvení po tahu, mezery a velikost znaků, test s psaním, nový editor klávesy.

## 1.6 – 2026-11-15
Oprava slova po tahu, volná pozice nápovědy, bubliny, nový přepínač, test náhledu.

## 1.5 – 2026-11-15
Nové členění nastavení, záložky vzhledu a rozložení, spodní řada, tahy v záloze.

## 1.4 – 2026-11-15
Znaky při podržení, pozice nápovědy, barvy neonu a tahu, oprava hlášky v liště.

## 1.3 – 2026-11-15
Rozložení kláves, hlasové povely, neon při klepnutí, postupná animace swipe.

## 1.2 – 2026-11-15
Záloha a obnova, záznam tahů pro ladění swipe, nástroj `tune`.

## 1.1 – 2026-11-15
Makra a zkratky, trigramy, učení z oprav, přizpůsobení swipe, živý náhled, tlačítko + pro rychlé přidání slova, diktování bez držení Enteru.

## 1.0 – 2026-11-14
První plně funkční offline swipe klávesnice (Rust jádro + Kotlin), pak přejmenování na reallyFAST!, vzhled a animace (Fáze 2), výchozí modrá, diktování podržením Enteru.

> Verze 1.9 a 1.10 se v historii commitů nevyskytují; jejich změny jsou součástí verze 1.11.
