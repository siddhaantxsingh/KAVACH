package kavach.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Gradle/JUnit entry point for the parity checks (`./gradlew :core:test`). */
class ParityTest {
    @Test
    fun kotlinCoreMatchesPythonReference() {
        val assets = listOf(File("../../assets"), File("../assets"), File("assets")).first { File(it, "parity/vectors.json").exists() }
        val fails = ParityChecks.run(assets)
        assertTrue(fails.take(20).joinToString("\n"), fails.isEmpty())
    }
}
