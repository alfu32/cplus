package cplus.intellij

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
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
                parserCommand = "cplus parse",
                importGraphCommand = "cplus graph",
                languageServerCommand = "cplus lsp"
            )
        )

        assertEquals("cpc compile", settings.current().compilerCommand)
        assertEquals("cpc run", settings.current().runnerCommand)
        assertEquals("cpc test", settings.current().testProgram)
        assertEquals("cpc parse", settings.current().parserCommand)
        assertEquals("cpc graph", settings.current().importGraphCommand)
        assertEquals("cpc lsp", settings.current().languageServerCommand)
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

    @Test
    fun normalizesLegacyStateEvenWhenItWasAlreadyLoadedBeforeTheMigration() {
        val settings = CPlusSettings()
        settings.update(
            compiler = "cplus compile",
            runner = "cplus run",
            program = "cplus test",
            parser = "",
            importGraph = "cplus graph",
            languageServer = ""
        )

        assertEquals("cpc compile", settings.current().compilerCommand)
        assertEquals("cpc run", settings.current().runnerCommand)
        assertEquals("cpc test", settings.current().testProgram)
        assertEquals("cpc graph", settings.current().importGraphCommand)
    }

    @Test
    fun keepsCustomCommandsWhenReadingStateRepeatedly() {
        val settings = CPlusSettings()
        val custom = CPlusSettings.State(
            compilerCommand = "./tools/cplus compile",
            runnerCommand = "mise exec -- cpc run",
            testProgram = "./scripts/run-tests.sh",
            parserCommand = "",
            importGraphCommand = "./tools/graph",
            languageServerCommand = ""
        )

        settings.loadState(custom)

        assertEquals(custom, settings.current())
        assertEquals(custom, settings.current())
    }

    @Test
    fun migratesLegacyCommandsWithIrregularWhitespace() {
        val settings = CPlusSettings()
        settings.loadState(
            CPlusSettings.State(
                testProgram = "cplus  test",
                parserCommand = "  cplus\tparse ",
                languageServerCommand = "cplus   lsp"
            )
        )

        assertEquals("cpc test", settings.current().testProgram)
        assertEquals("cpc parse", settings.current().parserCommand)
        assertEquals("cpc lsp", settings.current().languageServerCommand)
    }

    @Test
    fun gutterActionsUseCpcForBlankBuiltInCommands() {
        val settings = CPlusSettings.State(compilerCommand = "", runnerCommand = "", testProgram = "")

        assertEquals("cpc test", CPlusTestRunLineMarkerContributor.commandText(settings, CPlusTestFixture("fixture", 0, 1)))
        assertEquals("cpc run", CPlusTestRunLineMarkerContributor.commandText(settings, null))
    }

    @Test
    fun persistsEnvironmentOverridesAlongsideCommands() {
        val settings = CPlusSettings()
        settings.update("cc", "run", "test", "parse", "graph", "lsp", "CC=gcc\nCPLUS_TRACE=1")

        assertEquals("CC=gcc\nCPLUS_TRACE=1", settings.current().environment)
        assertEquals(settings.current(), settings.getState())
    }

    @Test
    fun parsesEnvironmentOverridesAndRejectsMalformedEntries() {
        assertEquals(
            mapOf("CC" to "gcc", "EMPTY" to "", "CPLUS_TRACE" to "1=2"),
            CPlusCommand.parseEnvironment("# comment\nCC=gcc\nEMPTY=\nCPLUS_TRACE=1=2")
        )
        assertThrows(IllegalArgumentException::class.java) { CPlusCommand.parseEnvironment("not-an-assignment") }
        assertThrows(IllegalArgumentException::class.java) { CPlusCommand.parseEnvironment("1BAD=value") }
    }

    @Test
    fun commandProbeReportsExitCodeAndOutput() {
        val result = CPlusCommand.probe("sh -c 'printf probe; exit 7'", "PROBE_ENV=ok", timeoutSeconds = 2)

        assertEquals(7, result.exitCode)
        assertTrue(result.output.contains("probe"))
        assertEquals("ok", result.environment["PROBE_ENV"])
    }

    @Test
    fun commandProbeRunsSemicolonSeparatedConfigurationStatements() {
        val result = CPlusCommand.probe("printf first; printf second", "", timeoutSeconds = 2)

        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("firstsecond"))
    }
}
