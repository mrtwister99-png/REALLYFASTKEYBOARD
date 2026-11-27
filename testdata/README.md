# Testovaci data

`testovaci-slovnik.json` - 1000 slov pro zkousku osobniho slovniku (500 vymyslenych, vcetne rodin slov
s predponami krakon-, zlatov-, mrkvic-, tulipan-, bambul-; 500 vzacnejsich skutecnych ceskych slov).

Nahrani do telefonu: `adb push testdata/testovaci-slovnik.json /sdcard/Download/`, pak v aplikaci
Slovnik -> Importovat a vybrat soubor. Smazani: Slovnik -> Smazat vse.

Co zkouset: napsat `krak` (nabidne krakon...), `tuli`, `bamb`; slova z testu maji byt v liste nabidek
casteji nez bezna slova se stejnou predponou; po mezere v prazdnem kontextu se nabidnou nejpouzivanejsi osobni slova.
