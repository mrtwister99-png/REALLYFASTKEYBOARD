# Seznam nastavení

Přehled všech nastavení fAZT! (`settings.json`). **Jediný zdroj pravdy je struktura `Settings` v `crates/keyboard-core/src/settings.rs`**, kde se hodnoty při načtení i zápisu ořezávají (`validate`). Tenhle soubor vznikl ze zdrojů; po přidání nastavení ho udržuj v souladu (postup je v [architecture.md](architecture.md), kapitola „Jak přidat nové nastavení“).

- **Prázdná barva** znamená „automatická“ nebo „vypnuto“, podle řádku.
- **`rainbow`** je zvláštní hodnota barvy (duha); povolená je jen tam, kde je to v tabulce uvedeno.
- **Úrovně:** 1 = základ (vždy vidět v Hlavní), 2 = Pokročilé (sbalená karta v Hlavní), 3 = Waiting (expertní záložka).
- **Styly 1–5** ukládají jen klíče ze seznamu `STYLE_KEYS` v `settings.rs`: barvy, přechody, obrys, efekty, rozměry, polohy znaků a nápověd, tloušťky písma. Jazyk, rozložení, makra, přemapování kláves a chování psaní se do stylů neukládají.

Počet nastavení: **94**.


## Jazyk, rozložení, spodní řada

| Klíč | Výchozí | Rozsah / hodnoty | Co dělá | Kde v aplikaci |
|---|---|---|---|---|
| `language` | `cs+en` | `cs`, `en`, `cs+en` | Jazyk psaní a slovníku. | Hlavní → Jazyk; dlouhý stisk mezerníku |
| `layout` | `qwertz` | `qwertz`, `qwerty` | Rozložení písmen. | Hlavní → Jazyk |
| `show_number_row` | `true` | zap / vyp | Řada čísel nad písmeny. | Hlavní → Jazyk |
| `number_divider` | `true` | zap / vyp | Čára mezi řadou čísel a písmeny (jen když je řada čísel zapnutá). | Hlavní → Jazyk |
| `macro_key` | `right` | `left`, `right`, `hidden` | Kde je ve spodní řadě tlačítko maker (výchozí vpravo vedle mezerníku); podržením otevře emoji. Skryté: makra jsou v nastavení, emoji podržením ?123. | Hlavní → Spodní řada |

## Psaní a tah

| Klíč | Výchozí | Rozsah / hodnoty | Co dělá | Kde v aplikaci |
|---|---|---|---|---|
| `swipe_enabled` | `true` | zap / vyp | Psaní tahem přes písmena. | Psaní |
| `swipe_sensitivity` | `1.0` | 0,5 – 1,5 | Citlivost psaní tahem. | Psaní |
| `swipe_adapt` | `true` | zap / vyp | Přizpůsobení tahů tvému stylu: posouvá středy kláves podle potvrzených tahů. | Psaní |
| `swipe_log` | `false` | zap / vyp | Záznam tahů pro ladění vah (jen v zařízení, drží se posledních 1000 tahů). | Hlavní obrazovka → Záloha a ladění |
| `autocorrect` | `true` | zap / vyp | Nabízení oprav překlepů v liště (nic se samo nepřepisuje). | Psaní |
| `auto_diacritics` | `true` | zap / vyp | Mezerník (nebo interpunkce) za neznámým slovem bez diakritiky ho opraví na jedinou podobu s diakritikou (kdyz → když). Známá a osobní slova se nemění. | Psaní |
| `predictions` | `true` | zap / vyp | Predikce dalšího slova. | Psaní |
| `learn_from_typing` | `true` | zap / vyp | Učení nových slov z psaní. | Psaní |
| `show_suggestions` | `true` | zap / vyp | Lišta s návrhy nad klávesnicí. | Psaní |
| `auto_capitalize` | `true` | zap / vyp | Automatická velká písmena podle pole. | Psaní |
| `double_space_to_period` | `true` | zap / vyp | Dvojitá mezera vloží tečku a mezeru. | Psaní |
| `long_press_delay_ms` | `250` | 150 – 500 | Prodleva dlouhého stisku (ms). | Psaní |
| `vibration_ms` | `10` | 0 – 50 | Délka vibrace při stisku (ms), 0 = bez vibrace. | Psaní |
| `sound_enabled` | `false` | zap / vyp | Zvuk kláves. | Psaní |
| `voice_input` | `true` | zap / vyp | Diktování podržením Enteru. | Hlas |
| `voice_button` | `true` | zapnuto / vypnuto | Diktování tlačítkem s mikrofonem v liště vedle schránky (nezávisle na podržení Enteru, které řídí `voice_input`). | Hlas |

## Gesta a schránka

| Klíč | Výchozí | Rozsah / hodnoty | Co dělá | Kde v aplikaci |
|---|---|---|---|---|
| `gesture_delete_word` | `true` | zap / vyp | Gesto: tah vlevo z ⌫ smaže slovo. | Psaní |
| `gesture_space_cursor` | `true` | zap / vyp | Gesto: tah po mezerníku posouvá kurzor. | Psaní |
| `gesture_swipe_up_caps` | `true` | zap / vyp | Gesto: švih nahoru přepne Caps. | Psaní |
| `gesture_swipe_down_hide` | `true` | zap / vyp | Gesto: švih dolů schová klávesnici. | Psaní |
| `gesture_pinch_resize` | `true` | zap / vyp | Gesto: pinch mění výšku klávesnice. | Psaní |
| `clipboard_enabled` | `true` | zap / vyp | Historie schránky. | Hlavní obrazovka → Schránka |
| `clipboard_ttl_minutes` | `60` | 1 – 1440 | Za jak dlouho se položky schránky automaticky mažou (min). | Hlavní obrazovka → Schránka |

## Rozměry a polohy

| Klíč | Výchozí | Rozsah / hodnoty | Co dělá | Kde v aplikaci |
|---|---|---|---|---|
| `key_height_dp` | `56` | 48 – 72 | Výška kláves (dp). | Hlavní → Rozměry („Výška klávesnice") |
| `bar_height_dp` | `44` | 36 – 64 | Výška lišty návrhů nad klávesami (dp). | Rozložení a písmo → III. Spodní řada |
| `keyboard_height_percent` | `38` | 30 – 50 | Celková výška klávesnice v % výšky obrazovky; mění se gestem pinch. | gesto pinch |
| `key_radius_dp` | `10` | 2 – 22 | Zaoblení kláves (dp). | Hlavní → Rozměry |
| `key_text_percent` | `100` | 70 – 150 | Velikost znaků na klávesách (%). | Hlavní → Rozměry |
| `key_font` | `system` | `system` nebo název souboru z `assets/fonts` | Písmo znaků na klávesách i malé nápovědy (bez cesty; neplatný název = systémové). | Rychle |
| `key_gap_dp` | `5` | 1 – 10 | Vodorovná mezera mezi klávesami (dp). | Pokročilé → Rozměry a detaily |
| `row_gap_dp` | `8` | 0 – 16 | Svislá mezera mezi řadami (dp); menší mezera zmenší i celkovou výšku klávesnice (řada = `key_height_dp` + `row_gap_dp` − 8); u starého souboru se odvodí z `key_gap_dp`. | Pokročilé → Rozměry a detaily |
| `row_offsets` | `[0,0,0,0,0]` | 5 čísel −1 – 1 | Posun jednotlivých řad nahoru (−) a dolů (+) v násobcích výšky řady, číslováno od spodní řady (0 = mezerník, 4 = číslice). Při použití se omezí: nejvyšší řada jde jen kousek nahoru, spodní kousek dolů a řady se nesmí překrýt. | Rozložení a písmo III → Upravit výšku řádků (patří do profilu 1–5) |
| `key_label_x` | `0.5` | 0 – 1 | Vodorovná poloha hlavního znaku na klávese (0,5 = uprostřed). | Pokročilé → Polohy a nápovědy |
| `key_label_y` | `0.5` | 0 – 1 | Svislá poloha hlavního znaku na klávese (0,5 = uprostřed). | Pokročilé → Polohy a nápovědy |
| `label_weight` | `400` | 100 – 900 po 100 | Tloušťka písma znaků na písmenech. | Pokročilé → Rozměry a detaily |
| `digit_weight` | `400` | 100 – 900 po 100 | Tloušťka písma číslic. | Pokročilé → Rozměry a detaily |
| `key_hint_x` | `1.0` | −1 – 2 | Vodorovná poloha nápovědy (0 vlevo, 1 vpravo; mimo 0..1 = za okrajem klávesy, nejdále −1..2). | Pokročilé → Polohy a nápovědy |
| `key_hint_y` | `0.0` | −1 – 2 | Svislá poloha nápovědy (0 nahoře, 1 dole; mimo 0..1 = za okrajem klávesy, nejdále −1..2). | Pokročilé → Polohy a nápovědy |
| `hint_percent` | `100` | 60 – 180 | Velikost nápovědy (%). Podle ní se kreslí i obrázek makra na podržení. | Pokročilé → Polohy a nápovědy |
| `key_hint_pos` | `top_right` | `top_right`, `top_left`, `top_center`, `bottom_right`, `bottom_left`, `bottom_center` | Starý způsob určení polohy nápovědy; slouží už jen k migraci na `key_hint_x/y`. | neviditelné |

## Základní barvy a styly (úroveň 1)

| Klíč | Výchozí | Rozsah / hodnoty | Co dělá | Kde v aplikaci |
|---|---|---|---|---|
| `theme` | `dark` | `dark`, `light`, `oled_black`, `custom` | Téma: tmavé, světlé, OLED černé nebo vlastní barvy. | Hlavní → Barvy (hotové styly) |
| `accent_color` | `#0080FF` | `#RRGGBB` | Accent barva (zvýraznění, Enter, přepínače). | Hlavní → Barvy |
| `custom_background` | `#111113` | `#RRGGBB` | Barva pozadí ve vlastním tématu. | Hlavní → Barvy |
| `custom_key` | `#26262B` | `#RRGGBB` | Barva kláves ve vlastním tématu. | Hlavní → Barvy |
| `custom_text` | `#F4F4F5` | `#RRGGBB` | Barva textu ve vlastním tématu. | Hlavní → Barvy |
| `style_slots` | `5 výchozích stylů` | pole 5 objektů | Moje styly 1–5: pět uložených vzhledů (každý obsahuje právě klíče z `STYLE_KEYS`). Chybějící nebo neplatné sloty se doplní výchozími (slot 1 = výchozí vzhled, sloty 2–5 = výchozí vzhled s jednou jinou barvou; nezměněné starší výchozí sloty s plnými barvami se při načtení nahradí novými). | Hlavní → Barvy → Moje styly |

## Přepínače vzhledu a efekty (úroveň 1)

| Klíč | Výchozí | Rozsah / hodnoty | Co dělá | Kde v aplikaci |
|---|---|---|---|---|
| `key_popup` | `true` | zap / vyp | Bublina s písmenem nad stisknutou klávesou. | Hlavní → Klávesy a efekty |
| `key_hints` | `true` | zap / vyp | Malé nápovědy znaků pro dlouhý stisk v rozích kláves. | Hlavní → Klávesy a efekty |
| `key_shadow` | `true` | zap / vyp | Jemný stín pod klávesami. | Hlavní → Klávesy a efekty |
| `animations` | `true` | zap / vyp | Plynulé animace (stisk, přechody panelů, návrhy). | Hlavní → Klávesy a efekty |
| `special_outline` | `true` | zap / vyp | Obrys speciálních kláves (Shift, ?123, ⌫) v barvě akcentu. | Hlavní → Klávesy a efekty |
| `swipe_trail` | `true` | zap / vyp | Stopa za prstem při tahu. | Hlavní → Klávesy a efekty |
| `trail_style` | `line` | `line`, `dashed`, `fire`, `pixel` | Vzhled stopy za prstem: čára, čárkovaná, hořící čára nebo pixely. | Animace → Swipe I |
| `swipe_glow` | `true` | zap / vyp | Rozsvícení kláves při tahu a animace po úspěšném tahu (zapíná se výběrem animace, „Žádná“ ji vypne). | Hlavní → Animace po úspěšném tahu |
| `key_glow` | `true` | zap / vyp | Efekt po klepnutí (zapíná se výběrem efektu). | Hlavní → Efekt při klepnutí |
| `tap_effect` | `neon` | `neon`, `glitch`, `paint`, `ghost`, `orbit`, `pop`, `cut`, `slide` | Který efekt se spustí po klepnutí (když je zapnutý `key_glow`). `orbit` (obrys proběhne kolem klávesy) a `pop` (písmeno se rozprskne na jiskry s náhodnými směry, rychlostmi, časy a zakřivením drah a znovu se zrodí). | Hlavní → Efekt při klepnutí (rozbalovací seznam) |
| `neon_color` | `#0080FF` | `#RRGGBB` | Barva efektu po klepnutí (Neon, Glitch, Duch). | Hlavní → Klávesy a efekty |
| `glow_style` | `neon` | `neon`, `paint`, `ghost`, `flip` | Animace po úspěšném tahu (když je zapnutý `swipe_glow`): Neon, Výplň (`paint`), Text (`ghost` = písmena složí slovo nad klávesnicí), Otočení písmen (`flip` = písmena slova se za sebou otočí o 90–180°). Starší hodnoty se převedou na `neon`. | Animace → Swipe I |
| `glow_color` | `#0080FF` | `#RRGGBB` nebo `rainbow` | Barva písmen po úspěšném tahu. Smí být `rainbow`. | Hlavní (duha jen s Waiting) |

## Pokročilé barvy a obrys (úroveň 2)

| Klíč | Výchozí | Rozsah / hodnoty | Co dělá | Kde v aplikaci |
|---|---|---|---|---|
| `hint_color` | *(prázdné)* | `#RRGGBB` nebo prázdné | Barva znaků nápovědy; prázdná = automatická. | Barvy II |
| `trail_color` | *(prázdné)* | `#RRGGBB` nebo prázdné | Barva stopy za prstem; prázdná = accent. | Pokročilé → Rozměry a detaily |
| `popup_color` | *(prázdné)* | `#RRGGBB` nebo prázdné | Barva bubliny nad klávesou; prázdná = automatická podle klávesy. | Pokročilé → Rozměry a detaily |
| `alts_color` | *(prázdné)* | `#RRGGBB` nebo prázdné | Barva lišty znaků při podržení klávesy; prázdná = automatická. | Pokročilé → Rozměry a detaily |
| `digit_text_color` | *(prázdné)* | `#RRGGBB` nebo prázdné | Barva znaků číslicových kláves; prázdná = automatická. | Pokročilé → Číslice |
| `digit_key_color` | *(prázdné)* | `#RRGGBB` nebo prázdné | Barva číslicových kláves; prázdná = automatická. | Pokročilé → Číslice |
| `digit_key_color2` | *(prázdné)* | `#RRGGBB`, `rainbow` nebo prázdné | Druhá barva kláves čísel: přechod zleva doprava. | Barvy II |
| `digit_text_color2` | *(prázdné)* | `#RRGGBB`, `rainbow` nebo prázdné | Druhá barva znaků čísel (přechod zleva doprava). | Barvy II |
| `bg_grad_dir`, `key_grad_dir`, `digit_key_grad_dir`, `text_grad_dir`, `digit_text_grad_dir`, `outline_grad_dir` | `top` | `top`, `bottom`, `left`, `right` | Odkud se rozbíhá přechod druhé barvy (pozadí, klávesy, klávesy čísel, znaky, znaky čísel, obrysy). | Waiting (jen pozadí, znaky a klávesy) |
| `outline_color` | *(prázdné)* | `#RRGGBB` nebo prázdné | Barva stálého obrysu kláves; prázdná = bez obrysu. | Pokročilé → Obrys kláves |
| `outline_width_dp` | `2` | 1 – 6 | Tloušťka obrysu (dp). | Pokročilé → Obrys kláves |
| `paint_color1` | `#0080FF` | `#RRGGBB` | Efekt Barvy: první barva v pořadí. | Pokročilé → Efekty |
| `paint_color2` | `#FF5100` | `#RRGGBB` | Efekt Barvy: druhá barva v pořadí. | Pokročilé → Efekty |
| `paint_color3` | `#6A02E8` | `#RRGGBB` | Efekt Barvy: třetí barva v pořadí. | Pokročilé → Efekty |
| `paint_color4` | `#22C55E` | `#RRGGBB` | Efekt Barvy: čtvrtá barva v pořadí. | Animace po kliknutí III |
| `paint_color5` | `#FFD60A` | `#RRGGBB` | Efekt Barvy: pátá barva v pořadí. | Animace po kliknutí III |
| `paint_color6` | `#EC4899` | `#RRGGBB` | Efekt Barvy: šestá barva v pořadí. | Animace po kliknutí III |

## Druhé barvy

| Klíč | Výchozí | Rozsah / hodnoty | Co dělá | Kde v aplikaci |
|---|---|---|---|---|
| `key_color2` | *(prázdné)* | `#RRGGBB`, `rainbow` nebo prázdné | Druhá barva kláves: přechod shora (barva klávesy) dolů. | Barvy II |
| `text_color2` | *(prázdné)* | `#RRGGBB`, `rainbow` nebo prázdné | Druhá barva znaků: přechod shora (barva textu) dolů. | Barvy II |
| `background_color2` | *(prázdné)* | `#RRGGBB`, `rainbow` nebo prázdné | Druhá barva pozadí: přechod shora dolů; poslední odstín je i pod klávesnicí a v navigační liště. | Barvy II |
| `neon_color2` | *(prázdné)* | `#RRGGBB`, `rainbow` nebo prázdné | Druhá barva efektu po klepnutí: Neon, Výbuch, Řez a Slide střídají obě barvy, Glitch losuje barvu u každého záblesku, Duch střídá barvu dozvuků; Obvod ji má jako přechod uvnitř klávesy. `rainbow` = náhodný odstín. | Animace po kliknutí III |
| `trail_color2` | *(prázdné)* | `#RRGGBB` nebo prázdné | Druhá barva stopy: konec stopy za prstem přechází do této barvy. | Swipe I |
| `glow_color2` | *(prázdné)* | `#RRGGBB` nebo prázdné | Druhá barva písmen po tahu: písmena slova se střídají mezi `glow_color` a touto barvou (1., 3. … první barva, 2., 4. … druhá). | Swipe I |
| `tap_text_color2` | *(prázdné)* | `#RRGGBB` nebo prázdné | Druhá barva znaku po klepnutí: klepnutí střídají obě barvy. | Animace po kliknutí II |
| `outline_color2` | *(prázdné)* | `#RRGGBB`, `rainbow` nebo prázdné | Druhá barva obrysu: přechod shora (barva obrysu) dolů. | Barvy II |
| `outline_tap_color2` | *(prázdné)* | `#RRGGBB` nebo prázdné | Druhá barva obrysu po klepnutí: klepnutí střídají obě barvy. | Animace po kliknutí III |

## Barvy podle prstu

| Klíč | Výchozí | Rozsah / hodnoty | Co dělá | Kde v aplikaci |
|---|---|---|---|---|
| `tap_text_color` | *(prázdné)* | `#RRGGBB` nebo prázdné | Barva znaku po klepnutí (drží asi 2,5 s, pak 0,4 s bledne); prázdná = vypnuto. | Animace po kliknutí I |
| `hover_text_color` | *(prázdné)* | `#RRGGBB` nebo prázdné | Barva znaku klávesy pod prstem při tahu; prázdná = automatická. Funguje jen se zapnutým `swipe_glow`. | Swipe II |
| `passed_text_color` | *(prázdné)* | `#RRGGBB` nebo prázdné | Barva znaku kláves, přes které prst přejel při tahu (po tahu bledne asi 0,5 s). Funguje jen se zapnutým `swipe_glow`. | Swipe II |
| `outline_tap_color` | *(prázdné)* | `#RRGGBB` nebo prázdné | Barva obrysu po klepnutí (drží asi 2,5 s, pak 0,4 s bledne). | Animace po kliknutí I |
| `outline_hover_color` | *(prázdné)* | `#RRGGBB` nebo prázdné | Barva obrysu klávesy pod prstem. Funguje jen se zapnutým `swipe_glow`. | Swipe II |
| `outline_passed_color` | *(prázdné)* | `#RRGGBB` nebo prázdné | Barva obrysu kláves, přes které prst přejel při tahu. Funguje jen se zapnutým `swipe_glow`. | Swipe II |

## Waiting (úroveň 3)

| Klíč | Výchozí | Rozsah | Popis | Kde v aplikaci |
|---|---|---|---|---|
| `char_outline` | `false` | zap / vyp | Znak na klávese má tenký obrys. | Waiting → Znak |
| `char_outline_color` | *(prázdné)* | `#RRGGBB` nebo prázdné | Barva obrysu znaku; prázdná = barva obrysu klávesy (bez ní akcent). | Waiting → Znak |
| `bg_grad_dir`, `text_grad_dir`, `key_grad_dir` | `top` | `top`, `bottom`, `left`, `right` | Začátek přechodu druhé barvy pro pozadí, znaky a klávesy. | Waiting → Začátek přechodu |

## Úprava kláves a makra

| Klíč | Výchozí | Rozsah / hodnoty | Co dělá | Kde v aplikaci |
|---|---|---|---|---|
| `key_map` | `{}` | mapa, nejvýše 64 záznamů | Přemapování kláves: původní znak pozice → znak, který tam je. | Hlavní → Úprava kláves |
| `key_alts` | `{}` | mapa, nejvýše 64 záznamů, 12 variant | Znaky pro dlouhý stisk: původní znak pozice → řetězec variant (prázdný = žádné). | Hlavní → Úprava kláves |
| `swap_mode_enter` | `false` | zap / vyp | Prohozené pozice ?123/ABC a Enter. | Hlavní → Úprava kláves (s potvrzením) |
| `swap_shift_delete` | `false` | zap / vyp | Prohozené pozice Shift (nebo přepínače symbolů) a ⌫. | Hlavní → Úprava kláves (s potvrzením) |
| `key_macros` | `{}` | mapa, nejvýše 16 záznamů | Makro přiřazené klávese na klik: původní znak pozice → id makra. | Hlavní → Úprava kláves → Makro |
| `key_macros_hold` | `{}` | mapa, nejvýše 16 záznamů | Makro přiřazené klávese na podržení: původní znak pozice → id makra. | Hlavní → Úprava kláves → Makro |
| `macro_faces` | `{}` | mapa, nejvýše 256 záznamů, hodnoty 0 – 3 | Obrázek makra (zvolený při jeho tvorbě): id makra → 0 trol, 1 chocho, 2 dollo, 3 maskot. | Makra a zkratky → editor makra |

## Interní

| Klíč | Výchozí | Rozsah / hodnoty | Co dělá | Kde v aplikaci |
|---|---|---|---|---|
| `settings_version` | `2` | interní | Verze schématu nastavení; slouží k migracím výchozích hodnot (starý soubor bez pole = 0). | neviditelné |
