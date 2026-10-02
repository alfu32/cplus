package cplus.intellij

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.util.concurrent.atomic.AtomicInteger

/** One automatic recovery per explicit start/configuration window, not an infinite crash loop. */
@Service(Service.Level.PROJECT)
internal class CPlusLspRecovery {
    private val attempts = AtomicInteger()
    fun reset() { attempts.set(0) }
    fun claimRestart(): Boolean = attempts.compareAndSet(0, 1)

    companion object {
        fun getInstance(project: Project): CPlusLspRecovery = project.getService(CPlusLspRecovery::class.java)
    }
}
