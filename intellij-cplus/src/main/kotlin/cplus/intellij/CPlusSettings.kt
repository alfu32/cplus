package cplus.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.ProjectManager
import com.intellij.platform.lsp.api.LspClientManager
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JComponent
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JScrollPane
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SwingUtilities

@State(name = "CPlusSettings", storages = [Storage("cplus.xml")])
class CPlusSettings : PersistentStateComponent<CPlusSettings.State> {
    data class State(
        var compilerCommand: String = "cpc compile",
        var runnerCommand: String = "cpc run",
        var testProgram: String = "cpc test",
        var parserCommand: String = "cpc parse",
        var importGraphCommand: String = "cpc graph",
        // The provider appends the protocol subcommand.  Existing `cpc lsp`
        // values are accepted and normalized by migrateLegacyDefaults().
        var languageServerCommand: String = "cpc",
        var environment: String = ""
    )

    private var state = State()
    override fun getState(): State = state
    override fun loadState(state: State) { this.state = state.migrateLegacyDefaults() }
    /**
     * Settings are persisted by IntelliJ and can outlive a plugin update.  Keep
     * the migration idempotent at the read boundary as well as in loadState so
     * callers never receive the obsolete built-in `cplus ...` commands.
     */
    fun current(): State {
        val migrated = state.migrateLegacyDefaults()
        if (migrated != state) state = migrated
        return state
    }
    fun update(compiler: String, runner: String, program: String, parser: String, importGraph: String, languageServer: String, environment: String = state.environment) {
        state = State(compiler, runner, program, parser, importGraph, languageServer, environment).migrateLegacyDefaults()
    }

    companion object {
        fun getInstance(): CPlusSettings = ApplicationManager.getApplication().getService(CPlusSettings::class.java)
    }

    private fun State.migrateLegacyDefaults(): State = copy(
        compilerCommand = compilerCommand.replaceLegacy("compile"),
        runnerCommand = runnerCommand.replaceLegacy("run"),
        testProgram = testProgram.replaceLegacy("test"),
        parserCommand = parserCommand.replaceLegacy("parse"),
        importGraphCommand = importGraphCommand.replaceLegacy("graph"),
        languageServerCommand = languageServerCommand.replaceLegacy("lsp")
    )

    private fun String.replaceLegacy(subcommand: String): String =
        when (trim().split(Regex("\\s+")).filter(String::isNotEmpty)) {
            listOf("cplus", subcommand) -> if (subcommand == "lsp") "cpc" else "cpc $subcommand"
            listOf("cpc", subcommand) -> if (subcommand == "lsp") "cpc" else this
            else -> this
        }
}

class CPlusSettingsConfigurable : Configurable {
    private var panel: JPanel? = null
    private var compiler = JTextField()
    private var runner = JTextField()
    private var testProgram = JTextField()
    private var parser = JTextField()
    private var importGraph = JTextField()
    private var languageServer = JTextField()
    private var environment = JTextArea()
    private var diagnostics = JTextArea()

    override fun getDisplayName(): String = "C-plus"

    override fun createComponent(): JComponent {
        val state = CPlusSettings.getInstance().current()
        compiler = JTextField(state.compilerCommand)
        runner = JTextField(state.runnerCommand)
        testProgram = JTextField(state.testProgram)
        parser = JTextField(state.parserCommand)
        importGraph = JTextField(state.importGraphCommand)
        languageServer = JTextField(state.languageServerCommand)
        environment = JTextArea(state.environment, 4, 60).apply {
            lineWrap = false
            toolTipText = "One NAME=VALUE per line; PATH=\$PATH:/path/to/bin extends PATH"
        }
        diagnostics = JTextArea(10, 60).apply {
            isEditable = false
            lineWrap = false
            wrapStyleWord = false
            toolTipText = "Results from the command test buttons"
        }
        return JPanel(GridBagLayout()).apply {
            val fields = listOf(
                "Compiler command" to compiler,
                "Runner command" to runner,
                "Test program command" to testProgram,
                "Parser command (optional)" to parser,
                "Import graph command" to importGraph,
                "Language server command" to languageServer
            )
            fields.forEachIndexed { row, (label, field) ->
                val labelConstraints = GridBagConstraints().apply {
                    gridx = 0; gridy = row * 2; anchor = GridBagConstraints.WEST
                    insets = Insets(if (row == 0) 0 else 12, 0, 4, 0)
                }
                add(JLabel(label), labelConstraints)
                val test = JButton("Test").apply {
                    toolTipText = "Run this command and show its result below"
                    addActionListener { testCommand(label, field.text) }
                }
                add(test, GridBagConstraints().apply {
                    gridx = 1; gridy = row * 2; anchor = GridBagConstraints.EAST
                    insets = Insets(if (row == 0) 0 else 12, 8, 4, 0)
                })
                val fieldConstraints = GridBagConstraints().apply {
                    gridx = 0; gridy = row * 2 + 1; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL
                    gridwidth = 2
                    insets = Insets(0, 0, 0, 0)
                }
                add(field, fieldConstraints)
            }
            val environmentRow = fields.size * 2
            add(JLabel("Environment (optional; e.g. PATH=\$PATH:/path/to/bin)"), GridBagConstraints().apply {
                gridx = 0; gridy = environmentRow; gridwidth = 2; anchor = GridBagConstraints.WEST
                insets = Insets(12, 0, 4, 0)
            })
            add(JScrollPane(environment), GridBagConstraints().apply {
                gridx = 0; gridy = environmentRow + 1; gridwidth = 2; weightx = 1.0; fill = GridBagConstraints.BOTH
            })
            val diagnosticsRow = environmentRow + 2
            add(JLabel("Command diagnostics"), GridBagConstraints().apply {
                gridx = 0; gridy = diagnosticsRow; gridwidth = 2; anchor = GridBagConstraints.WEST
                insets = Insets(12, 0, 4, 0)
            })
            add(JScrollPane(diagnostics), GridBagConstraints().apply {
                gridx = 0; gridy = diagnosticsRow + 1; gridwidth = 2; weightx = 1.0; weighty = 1.0; fill = GridBagConstraints.BOTH
            })
            panel = this
        }
    }

    override fun isModified(): Boolean {
        val state = CPlusSettings.getInstance().current()
        return compiler.text != state.compilerCommand || runner.text != state.runnerCommand ||
            testProgram.text != state.testProgram || parser.text != state.parserCommand ||
            importGraph.text != state.importGraphCommand || languageServer.text != state.languageServerCommand ||
            environment.text != state.environment
    }

    override fun apply() {
        // Validate before persisting, so Apply never silently stores an
        // environment the process launcher cannot consume.
        try {
            CPlusCommand.parseEnvironment(environment.text, System.getenv())
        } catch (error: IllegalArgumentException) {
            diagnostics.append("\nSettings not applied: ${error.message}\n")
            throw com.intellij.openapi.options.ConfigurationException(error.message ?: "Invalid C-plus environment")
        }
        val previous = CPlusSettings.getInstance().current().copy()
        CPlusSettings.getInstance().update(
            compiler.text.trim(), runner.text.trim(), testProgram.text.trim(), parser.text.trim(), importGraph.text.trim(),
            languageServer.text.trim(), environment.text
        )
        val saved = CPlusSettings.getInstance().current()
        diagnostics.append("\nSettings applied. Compiler: ${saved.compilerCommand}; LSP: ${saved.languageServerCommand}\n")
        if (previous.languageServerCommand != saved.languageServerCommand || previous.environment != saved.environment) {
            ApplicationManager.getApplication().invokeLater {
                ProjectManager.getInstance().openProjects.filterNot { it.isDisposed }.forEach { project ->
                    CPlusLspRecovery.getInstance(project).reset()
                    LspClientManager.getInstance(project)
                        .stopAndRestartClientsIfNeeded(CPlusLspIntegrationProvider::class.java)
                }
            }
        }
    }

    override fun reset() {
        val state = CPlusSettings.getInstance().current()
        compiler.text = state.compilerCommand
        runner.text = state.runnerCommand
        testProgram.text = state.testProgram
        parser.text = state.parserCommand
        importGraph.text = state.importGraphCommand
        languageServer.text = state.languageServerCommand
        environment.text = state.environment
    }

    override fun disposeUIResources() { panel = null }

    private fun testCommand(label: String, commandText: String) {
        diagnostics.append("\n[$label] testing: ${commandText.ifBlank { "<empty>" }}\n")
        diagnostics.caretPosition = diagnostics.document.length
        if (commandText.isBlank()) {
            diagnostics.append("ERROR: command is empty\n")
            return
        }
        val envText = environment.text
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = CPlusCommand.probe(commandText, envText)
            SwingUtilities.invokeLater {
                diagnostics.append(result.format())
                diagnostics.caretPosition = diagnostics.document.length
            }
        }
    }
}

internal data class CPlusCommandProbeResult(
    val command: List<String>?,
    val workingDirectory: String,
    val environment: Map<String, String>,
    val output: String,
    val exitCode: Int?,
    val error: String?
) {
    fun format(): String = buildString {
        if (command == null) append("resolved command: <not resolved>\n")
        else append("resolved command: ${command.joinToString(" ")}\n")
        append("working directory: $workingDirectory\n")
        if (environment.isNotEmpty()) append("environment overrides: ${environment.keys.sorted().joinToString(", ")}\n")
        if (error != null) append("ERROR: $error\n")
        if (output.isNotBlank()) append("output:\n$output\n")
        if (exitCode != null) append("exit code: $exitCode\n")
    }
}
