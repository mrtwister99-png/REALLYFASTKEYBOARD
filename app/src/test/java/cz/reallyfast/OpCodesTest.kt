package cz.reallyfast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Hlidani shody kodu operaci mezi Rustem (`lib.rs`) a Kotlinem (`RustBridge.kt`): cisla se opisuji rucne,
 * preklad by neshodu nepoznal. Cte oba zdrojove soubory, takze bezi i bez nativni knihovny.
 */
class OpCodesTest {
    private fun find(path: String): File {
        // Gradle spousti testy z adresare modulu `app`; pro jistotu zkusi i koren repozitare.
        return listOf(File("../$path"), File(path)).first { it.isFile }
    }

    private fun codes(file: File, regex: Regex): Map<String, Int> =
        file.readLines().mapNotNull { regex.find(it) }.associate { it.groupValues[1] to it.groupValues[2].toInt() }

    // Operace jen v Rustu (Kotlin je nepouziva).
    private val rustOnly = setOf("DICT_READY")

    @Test
    fun kotlinAndRustAgreeOnOperationCodes() {
        val rust = codes(find("crates/keyboard-core/src/lib.rs"), Regex("""pub const OP_([A-Z0-9_]+): i32 = (\d+);"""))
        val kotlin = codes(find("app/src/main/java/cz/reallyfast/RustBridge.kt"), Regex("""const val OP_([A-Z0-9_]+) = (\d+)"""))
        assertTrue("nenacetly se kody z Rustu", rust.size > 40)
        assertTrue("nenacetly se kody z Kotlinu", kotlin.size > 40)
        for ((name, code) in kotlin) {
            assertEquals("OP_$name: Kotlin a Rust se lisi", rust[name], code)
        }
        val missing = rust.keys - kotlin.keys - rustOnly
        assertTrue("v Kotlinu chybi: $missing", missing.isEmpty())
        assertEquals("kody musi byt v Rustu unikatni", rust.size, rust.values.toSet().size)
    }
}
