# Zásady ochrany soukromí – fAZT!

*Poslední úprava: 19. 11. 2026. Podklad pro Google Play a pro uživatele; před zveřejněním doplň jméno a kontakt vydavatele.*

## Stručně

fAZT! je klávesnice, která **funguje bez internetu**. Nesbírá žádná data o tobě, nemá reklamy, analytiku ani telemetrii a **nic nikam neodesílá**.
Všechno, co si klávesnice pamatuje, zůstává jen v tvém telefonu.

## Co aplikace ukládá (a kde)

Vše v privátním úložišti aplikace, ke kterému nemají přístup jiné aplikace:

| Data | K čemu slouží |
|---|---|
| Nastavení a uložené styly | vzhled a chování klávesnice (`settings.json`) |
| Osobní slovník, bigramy, trigramy | návrhy a psaní tahem se přizpůsobují slovům, která používáš |
| Opravy (co sis zvolil místo návrhu) | chybné slovo se příště nabídne níž |
| Makra, zkratky, hlasové povely | tvoje vlastní textové bloky a povely |
| Přizpůsobení tahů | průměrný posun středů kláves podle tvých tahů |
| Historie schránky | zkopírované texty (30 položek, ve výchozím stavu se po 60 minutách mažou; hesla a kódy se neukládají) |
| Nedávná emoji | seznam naposledy použitých |
| Záznam tahů | **jen když ho sám zapneš** (výchozí je vypnuto): dráhy tahů a potvrzená slova, nejvýše 1000 posledních, pro ladění přesnosti |

V polích pro hesla a v polích, která si říkají o „no personalized learning“, se nic neučí, nezaznamenává ani nezapisuje do slovníku a psaní tahem je vypnuté.

## Oprávnění

- **Vibrace** (`VIBRATE`): odezva při stisku kláves.
- **Mikrofon** (`RECORD_AUDIO`): pouze pro diktování, které spustíš sám podržením Enteru. Bez udělení oprávnění klávesnice funguje, jen bez diktování.
- **Internet aplikace nemá** (oprávnění `INTERNET` v ní není).

## Diktování

Diktování používá **službu rozpoznávání řeči tvého telefonu** (na Androidu 12 a novějším přednostně rozpoznávání přímo v zařízení). Pokud telefon nemá stažený
offline model češtiny, může systémová služba (Google nebo Samsung) poslat zvuk na vlastní servery; to řídí a popisují zásady dané služby, ne fAZT!.
Mikrofon se zapíná jen při diktování. Diktování lze v nastavení vypnout a oprávnění k mikrofonu kdykoli odebrat v systému.

## Zálohy

- Systémová záloha Androidu a přenos dat do jiného zařízení jsou pro aplikaci **vypnuté**.
- Vlastní záloha do souboru vzniká **jen na tvoje vyžádání** a ukládá se tam, kam ji uložíš. Obsahuje nastavení, slovník, opravy, makra, hlasové povely,
  přizpůsobení tahů a (je-li zapnutý) záznam tahů. Historie schránky v ní **není**. Soubor je čitelný text (JSON); chraň ho jako jakýkoli soubor s osobními daty.

## Zabezpečení

Data jsou v sandboxu aplikace a chrání je šifrování úložiště telefonu. **Osobní slovník aplikace sama dodatečně nešifruje.**
Odinstalování aplikace smaže všechna její data. Kdykoli lze také smazat jednotlivé části v nastavení (osobní slovník, schránka, záznam tahů, přizpůsobení tahů).

## Sdílení s třetími stranami

Žádné. Aplikace neobsahuje reklamní ani analytické knihovny a data nikomu nepředává.

## Děti

Aplikace nesbírá žádné osobní údaje, takže se nijak neliší pro děti a dospělé.

## Změny zásad

Změny se zapíšou do [CHANGELOGu](../CHANGELOG.md) a do data poslední úpravy nahoře.

## Kontakt

*(doplnit před zveřejněním)*
