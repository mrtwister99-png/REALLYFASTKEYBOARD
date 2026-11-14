//! Emoji: kategorie a vyhledavani podle nazvu (cesky i anglicky, bez ohledu na diakritiku).

use crate::charmap::fold_str;

pub const CATEGORY_RECENT: usize = 0;
pub const CATEGORY_NAMES: [&str; 6] = ["Nedávné", "Smajlíci", "Zvířata", "Jídlo", "Aktivity", "Symboly"];

type Table = &'static [(&'static str, &'static str)];

const SMILEYS: Table = &[
    ("😀", "usmev smich grin smile"), ("😃", "usmev radost smile happy"), ("😄", "smich radost laugh"),
    ("😁", "zuby usmev grin beam"), ("😆", "smich laugh"), ("😅", "pot smich sweat"),
    ("😂", "slzy smichu joy lol"), ("🤣", "valim se smichy rofl"), ("🙂", "mirny usmev slight smile"),
    ("🙃", "vzhuru nohama upside down"), ("😉", "mrknuti wink"), ("😊", "stydlivy usmev blush"),
    ("😇", "andel svatozar angel"), ("🥰", "laska srdicka love"), ("😍", "zamilovany srdce oci heart eyes"),
    ("😘", "pusa polibek kiss"), ("😋", "mnam chutne yum"), ("😜", "jazyk mrknuti tongue"),
    ("🤪", "blazen crazy zany"), ("🤔", "premysli hmm thinking"), ("🤫", "ticho pst shush"),
    ("🤗", "objeti hug"), ("🤭", "ups chichot giggle"), ("😐", "neutralni neutral"),
    ("😑", "bez vyrazu expressionless"), ("😶", "bez ust mlci silent"), ("😏", "uskleb smirk"),
    ("😒", "otraveny unamused"), ("🙄", "oci v sloup eye roll"), ("😬", "grimasa grimace"),
    ("😌", "uleva relieved"), ("😔", "smutny zamysleny pensive sad"), ("😴", "spi spanek sleep"),
    ("😷", "rouska nemocny mask sick"), ("🤒", "teplota nemocny fever"), ("🤢", "nevolno nausea"),
    ("🥵", "horko hot"), ("🥶", "zima cold"), ("😎", "bryle cool sunglasses"),
    ("🤓", "nerd sprt"), ("😕", "zmateny confused"), ("😟", "ustarany worried"),
    ("😮", "prekvapeny wow surprised"), ("😳", "cervena se flushed"), ("🥺", "prosim oci pleading"),
    ("😢", "slza place cry"), ("😭", "brec plac sob"), ("😱", "krik hruza scream"),
    ("😤", "nastvany para huff"), ("😡", "vztek zlost angry"), ("🤬", "nadava cursing"),
    ("😈", "dabel devil"), ("💀", "lebka skull"), ("💩", "hovinko poop"),
    ("🤡", "klaun clown"), ("👻", "duch ghost"), ("👽", "mimozemstan alien"),
    ("🤖", "robot"), ("👍", "palec nahoru ok like thumbs up"), ("👎", "palec dolu dislike thumbs down"),
    ("👏", "potlesk tleskani clap"), ("🙏", "prosim dekuji modlitba pray thanks"), ("🤝", "podani ruky handshake"),
    ("👋", "mavani ahoj wave hello"), ("✌️", "vitezstvi mir peace"), ("🤞", "drzim palce fingers crossed"),
    ("👌", "ok vyborne"), ("💪", "sila sval muscle strong"), ("🤷", "nevim shrug"),
    ("🤦", "facepalm dlan"),
];

const ANIMALS: Table = &[
    ("🐶", "pes dog"), ("🐱", "kocka cat"), ("🐭", "mys mouse"), ("🐹", "krecek hamster"),
    ("🐰", "kralik zajic rabbit"), ("🦊", "liska fox"), ("🐻", "medved bear"), ("🐼", "panda"),
    ("🐨", "koala"), ("🐯", "tygr tiger"), ("🦁", "lev lion"), ("🐮", "krava cow"),
    ("🐷", "prase pig"), ("🐸", "zaba frog"), ("🐵", "opice monkey"), ("🐔", "slepice chicken"),
    ("🐧", "tucnak penguin"), ("🐦", "ptak bird"), ("🐤", "kure chick"), ("🦆", "kachna duck"),
    ("🦅", "orel eagle"), ("🦉", "sova owl"), ("🐺", "vlk wolf"), ("🐗", "kanec boar"),
    ("🐴", "kun horse"), ("🦄", "jednorozec unicorn"), ("🐝", "vcela bee"), ("🦋", "motyl butterfly"),
    ("🐌", "snek snail"), ("🐞", "beruska ladybug"), ("🐜", "mravenec ant"), ("🕷️", "pavouk spider"),
    ("🐢", "zelva turtle"), ("🐍", "had snake"), ("🐙", "chobotnice octopus"), ("🐠", "ryba fish"),
    ("🐬", "delfin dolphin"), ("🐳", "velryba whale"), ("🦈", "zralok shark"), ("🐘", "slon elephant"),
    ("🦒", "zirafa giraffe"), ("🐑", "ovce sheep"), ("🐐", "koza goat"), ("🦌", "jelen deer"),
    ("🐿️", "veverka squirrel"), ("🦔", "jezek hedgehog"), ("🌲", "strom les tree"), ("🌸", "kvet sakura blossom"),
    ("🌹", "ruze rose"), ("🌻", "slunecnice sunflower"), ("🍀", "ctyrlistek stesti clover"), ("🍁", "list podzim leaf"),
    ("🌈", "duha rainbow"), ("☀️", "slunce sun"), ("🌙", "mesic moon"), ("⭐", "hvezda star"),
    ("🔥", "ohen fire"), ("❄️", "snih vlocka snow"), ("🌧️", "dest rain"), ("⚡", "blesk lightning"),
];

const FOOD: Table = &[
    ("🍎", "jablko apple"), ("🍐", "hruska pear"), ("🍊", "pomeranc orange"), ("🍋", "citron lemon"),
    ("🍌", "banan banana"), ("🍉", "meloun watermelon"), ("🍇", "hrozny grapes"), ("🍓", "jahoda strawberry"),
    ("🍒", "tresne cherry"), ("🍑", "broskev peach"), ("🍍", "ananas pineapple"), ("🥝", "kiwi"),
    ("🍅", "rajce tomato"), ("🥑", "avokado avocado"), ("🥕", "mrkev carrot"), ("🌽", "kukurice corn"),
    ("🥔", "brambora potato"), ("🧄", "cesnek garlic"), ("🧅", "cibule onion"), ("🍄", "houba mushroom"),
    ("🍞", "chleba bread"), ("🥐", "croissant rohlik"), ("🧀", "syr cheese"), ("🥚", "vejce egg"),
    ("🥓", "slanina bacon"), ("🍗", "kure stehno chicken"), ("🍖", "maso meat"), ("🌭", "parek hotdog"),
    ("🍔", "burger hamburger"), ("🍟", "hranolky fries"), ("🍕", "pizza"), ("🥪", "sendvic sandwich"),
    ("🌮", "taco"), ("🥗", "salat salad"), ("🍝", "spagety testoviny pasta"), ("🍜", "polevka nudle soup ramen"),
    ("🍣", "sushi"), ("🍚", "ryze rice"), ("🍰", "dort cake"), ("🎂", "narozeninovy dort birthday cake"),
    ("🧁", "muffin cupcake"), ("🍩", "kobliha donut"), ("🍪", "susenka cookie"), ("🍫", "cokolada chocolate"),
    ("🍬", "bonbon candy"), ("🍦", "zmrzlina ice cream"), ("🍿", "popcorn"), ("☕", "kava coffee"),
    ("🍵", "caj tea"), ("🥛", "mleko milk"), ("🍺", "pivo beer"), ("🍻", "na zdravi piva cheers"),
    ("🍷", "vino wine"), ("🥂", "pripitek sekt toast"), ("🍸", "koktejl cocktail"), ("🥤", "napoj limonada drink"),
    ("💧", "voda kapka water"), ("🧂", "sul salt"), ("🍯", "med honey"), ("🥄", "lzice spoon"),
];

const ACTIVITIES: Table = &[
    ("⚽", "fotbal football soccer"), ("🏀", "basketbal basketball"), ("🏐", "volejbal volleyball"), ("🎾", "tenis tennis"),
    ("🏒", "hokej hockey"), ("🏓", "ping pong stolni tenis"), ("⛳", "golf"), ("🏊", "plavani swim"),
    ("🚴", "kolo cyklistika bike"), ("🏃", "beh run"), ("🧘", "joga yoga"), ("🏋️", "posilovna cinky gym"),
    ("⛷️", "lyze ski"), ("🏂", "snowboard"), ("🎿", "lyzovani ski"), ("🥾", "turistika boty hiking"),
    ("🎮", "hry ovladac game"), ("🎲", "kostka dice"), ("♟️", "sachy chess"), ("🎯", "terc sipky target"),
    ("🎨", "malovani umeni art"), ("🎬", "film klapka movie"), ("🎤", "mikrofon zpev mic"), ("🎧", "sluchatka headphones"),
    ("🎵", "hudba nota music"), ("🎸", "kytara guitar"), ("🎹", "klavir piano"), ("🥁", "bubny drums"),
    ("📚", "knihy books"), ("✏️", "tuzka pencil"), ("💻", "notebook pocitac laptop"), ("📱", "mobil telefon phone"),
    ("📷", "fotak camera"), ("🎉", "oslava party"), ("🎊", "konfety confetti"), ("🎁", "darek gift"),
    ("🎈", "balonek balloon"), ("🎄", "vanoce stromek christmas"), ("🎃", "dyne halloween"), ("🏆", "pohar vitez trophy"),
    ("🥇", "zlato medaile gold"), ("🚗", "auto car"), ("🚕", "taxi"), ("🚌", "autobus bus"),
    ("🚆", "vlak train"), ("✈️", "letadlo plane"), ("🚀", "raketa rocket"), ("🚲", "kolo bicycle"),
    ("⛵", "lod plachetnice boat"), ("🏠", "dum domov home"), ("🏖️", "plaz beach"), ("⛰️", "hory mountain"),
    ("🏕️", "stan kemp camping"), ("🌍", "zeme svet earth"), ("🗺️", "mapa map"), ("⏰", "budik cas alarm"),
    ("💼", "prace kufrik work"), ("💰", "penize money"), ("🛒", "nakup kosik shopping"), ("🔑", "klic key"),
];

const SYMBOLS: Table = &[
    ("❤️", "srdce laska heart love"), ("🧡", "oranzove srdce"), ("💛", "zlute srdce"), ("💚", "zelene srdce"),
    ("💙", "modre srdce"), ("💜", "fialove srdce"), ("🖤", "cerne srdce"), ("🤍", "bile srdce"),
    ("💔", "zlomene srdce broken heart"), ("💕", "dve srdce"), ("💖", "trpytive srdce"), ("💯", "sto procent hundred"),
    ("✅", "hotovo ano check done"), ("❌", "ne krizek cross no"), ("❗", "vykricnik pozor exclamation"), ("❓", "otaznik question"),
    ("⚠️", "varovani warning"), ("🚫", "zakaz forbidden"), ("⛔", "stop zakaz vjezdu"), ("♻️", "recyklace recycle"),
    ("✨", "trpyt jiskry sparkles"), ("💥", "vybuch boom"), ("💤", "spanek zzz"), ("💬", "bublina zprava message"),
    ("👀", "oci koukam eyes"), ("➡️", "sipka vpravo right arrow"), ("⬅️", "sipka vlevo left arrow"), ("⬆️", "sipka nahoru up arrow"),
    ("⬇️", "sipka dolu down arrow"), ("🔄", "obnovit refresh"), ("➕", "plus"), ("➖", "minus"),
    ("✖️", "krat multiply"), ("➗", "deleno divide"), ("♾️", "nekonecno infinity"), ("©️", "copyright"),
    ("®️", "registrovano registered"), ("™️", "trademark ochranna znamka"), ("🔴", "cerveny kruh red"), ("🟢", "zeleny kruh green"),
    ("🔵", "modry kruh blue"), ("🟡", "zluty kruh yellow"), ("⚫", "cerny kruh black"), ("⚪", "bily kruh white"),
    ("🔒", "zamek lock"), ("🔓", "odemceno unlock"), ("🔔", "zvonek bell"), ("🔕", "ztlumeno mute"),
    ("📌", "pripinacek pin"), ("📎", "sponka paperclip"), ("🔗", "odkaz link"), ("📧", "email posta"),
    ("📞", "telefon volat call"), ("🕐", "hodiny cas clock"), ("📅", "kalendar calendar"), ("🇨🇿", "cesko vlajka czech flag"),
    ("🇸🇰", "slovensko vlajka slovakia"), ("🇪🇺", "evropa eu vlajka"), ("🇬🇧", "britanie anglie uk flag"), ("🇺🇸", "amerika usa flag"),
];

fn table(category: usize) -> Table {
    match category {
        1 => SMILEYS,
        2 => ANIMALS,
        3 => FOOD,
        4 => ACTIVITIES,
        5 => SYMBOLS,
        _ => &[],
    }
}

/// Emoji v kategorii 1..=5 (kategorie 0 "nedavne" je v ulozisti).
pub fn category(category: usize) -> Vec<&'static str> {
    table(category).iter().map(|(e, _)| *e).collect()
}

/// Vyhledavani podle nazvu; vsechna slova dotazu musi byt obsazena v popisu.
pub fn search(query: &str) -> Vec<&'static str> {
    let q = fold_str(query.trim());
    if q.is_empty() {
        return Vec::new();
    }
    let terms: Vec<&str> = q.split_whitespace().collect();
    let mut prefix_hits = Vec::new();
    let mut other_hits = Vec::new();
    for cat in 1..=5 {
        for (emoji, names) in table(cat) {
            if terms.iter().all(|t| names.contains(t)) {
                if names.split(' ').any(|n| n.starts_with(terms[0])) {
                    prefix_hits.push(*emoji);
                } else {
                    other_hits.push(*emoji);
                }
            }
        }
    }
    prefix_hits.extend(other_hits);
    prefix_hits.truncate(40);
    prefix_hits
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn categories_are_populated() {
        assert!(category(0).is_empty());
        for c in 1..=5 {
            assert!(category(c).len() >= 40, "kategorie {c}");
        }
        assert_eq!(CATEGORY_NAMES.len(), 6);
    }

    #[test]
    fn search_by_czech_and_english_name() {
        assert_eq!(search("pes")[0], "🐶");
        assert_eq!(search("dog")[0], "🐶");
        assert!(search("srdce").contains(&"❤️"));
        assert!(search("Kočka").contains(&"🐱")); // diakritika a velikost pismen nevadi
        assert!(search("zelene srdce").contains(&"💚"));
    }

    #[test]
    fn search_handles_empty_and_unknown() {
        assert!(search("").is_empty());
        assert!(search("   ").is_empty());
        assert!(search("qwxzqwxz").is_empty());
    }
}
