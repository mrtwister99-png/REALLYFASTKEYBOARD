# Changelog

Jedna věta za verzi, nejnovější nahoře. Čísla verzí odpovídají commitům (`vX.Y: …`).

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
