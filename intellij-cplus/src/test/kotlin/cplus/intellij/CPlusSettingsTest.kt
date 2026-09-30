package cplus.intellij

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CPlusSettingsTest {
    @Test
    fun migratesOnlyTheOldBuiltInCommands() {
        val settings = CPlusSettings()
        settings.loadState(
            CPlusSettings.State(
                compilerCommand = "cplus compile",
                runnerCommand = "cplus run",
                testProgram = "cplus test",
                parserCommand = "cplus",
                importGraphCommand = "cplus graph",
                languageServerCommand = "java -jar custom-lsp.jar"
            )
        )

        assertEquals("cpc compile", settings.current().compilerCommand)
        assertEquals("cpc run", settings.current().runnerCommand)
        assertEquals("cpc test", settings.current().testProgram)
        assertEquals("cplus", settings.current().parserCommand)
        assertEquals("cpc graph", settings.current().importGraphCommand)
        assertEquals("java -jar custom-lsp.jar", settings.current().languageServerCommand)
    }

    @Test
    fun preservesCustomCommandsVerbatim() {
        val settings = CPlusSettings()
        val custom = CPlusSettings.State(
            compilerCommand = "/opt/tools/cplus-wrapper compile",
            runnerCommand = "mise exec -- cpc run",
            testProgram = "./scripts/run-tests.sh",
            parserCommand = "java -jar /opt/parser.jar",
            importGraphCommand = "./tools/graph --json",
            languageServerCommand = "python3 lsp.py"
        )

        settings.loadState(custom)

        assertEquals(custom, settings.current())
    }
}
