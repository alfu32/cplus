package cplus.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import java.awt.GridLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField

@State(name = "CPlusSettings", storages = [Storage("cplus.xml")])
class CPlusSettings : PersistentStateComponent<CPlusSettings.State> {
    data class State(
        var compilerCommand: String = "cplus",
        var runnerCommand: String = "cplus test",
        var testProgram: String = ""
    )

    private var state = State()
    override fun getState(): State = state
    override fun loadState(state: State) { this.state = state }
    fun current(): State = state
    fun update(compiler: String, runner: String, program: String) {
        state = State(compiler, runner, program)
    }

    companion object {
        fun getInstance(): CPlusSettings = ApplicationManager.getApplication().getService(CPlusSettings::class.java)
    }
}

class CPlusSettingsConfigurable : Configurable {
    private var panel: JPanel? = null
    private var compiler = JTextField()
    private var runner = JTextField()
    private var testProgram = JTextField()

    override fun getDisplayName(): String = "C-plus"

    override fun createComponent(): JComponent {
        compiler = JTextField(CPlusSettings.getInstance().current().compilerCommand)
        runner = JTextField(CPlusSettings.getInstance().current().runnerCommand)
        testProgram = JTextField(CPlusSettings.getInstance().current().testProgram)
        return JPanel(GridLayout(0, 2, 8, 8)).apply {
            add(JLabel("Compiler command")); add(compiler)
            add(JLabel("Runner command")); add(runner)
            add(JLabel("Test program")); add(testProgram)
            panel = this
        }
    }

    override fun isModified(): Boolean {
        val state = CPlusSettings.getInstance().current()
        return compiler.text != state.compilerCommand || runner.text != state.runnerCommand || testProgram.text != state.testProgram
    }

    override fun apply() {
        CPlusSettings.getInstance().update(compiler.text.trim(), runner.text.trim(), testProgram.text.trim())
    }

    override fun reset() {
        val state = CPlusSettings.getInstance().current()
        compiler.text = state.compilerCommand
        runner.text = state.runnerCommand
        testProgram.text = state.testProgram
    }

    override fun disposeUIResources() { panel = null }
}
