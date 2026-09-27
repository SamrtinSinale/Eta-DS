package io.github.mangi.eta.ui.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.mangi.eta.agent.terminal.DetachedTaskSupervisor
import io.github.mangi.eta.agent.terminal.LinuxEnvironmentPaths
import io.github.mangi.eta.agent.terminal.LinuxPackageProfiles
import io.github.mangi.eta.agent.terminal.SharedFolderMounts
import io.github.mangi.eta.agent.terminal.linuxPackageProfileReady
import io.github.mangi.eta.agent.terminal.terminalEnvironment
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.repository.LinuxEnvironmentSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Activity 级状态所有者；配置变更只重建 UI，不替换正在运行的 Agent 会话。 */
internal class AgentAppViewModel(application: Application) : AndroidViewModel(application) {
    val state = AgentAppState(
        context = application,
        scope = viewModelScope,
    )
    private val terminalHost = TerminalSessionHost.get(application)
    val terminalStore = terminalHost.terminal
    val consoleStore = terminalHost.console
    val dshWebLauncher = DshWebLauncher(
        context = application,
        daemonSupervisor = DetachedTaskSupervisor(
            logger = AndroidAgentLogger,
            recordsFile = DetachedTaskSupervisor.defaultRecordsFile(application),
            linuxRootfsPathProvider = { environment ->
                environment.linuxDistribution?.let { distribution ->
                    LinuxEnvironmentPaths.rootfsDir(application, distribution).absolutePath
                }
            },
            linuxSharedMountsProvider = { SharedFolderMounts.current() },
        ),
    )

    var dshWebState by mutableStateOf(DshWebUiState())
        private set
    private var dshWebJob: Job? = null

    fun refreshDshWeb() {
        if (dshWebJob?.isActive == true) return
        viewModelScope.launch {
            val status = withContext(Dispatchers.IO) {
                val distribution = LinuxEnvironmentSettingsRepository.current(getApplication())
                val rootfs = LinuxEnvironmentPaths.rootfsDir(getApplication(), distribution)
                if (!linuxPackageProfileReady(rootfs, LinuxPackageProfiles.DSH)) {
                    DshWebUiState(DshWebPhase.NOT_INSTALLED)
                } else {
                    val runtime = dshWebLauncher.status(distribution.terminalEnvironment)
                    when {
                        runtime.running -> DshWebUiState(DshWebPhase.RUNNING)
                        runtime.code != null -> DshWebUiState(DshWebPhase.FAILED, runtime.code)
                        dshWebState.phase == DshWebPhase.FAILED -> dshWebState
                        else -> DshWebUiState(DshWebPhase.READY)
                    }
                }
            }
            if (dshWebJob?.isActive != true) dshWebState = status
        }
    }

    fun launchDshWeb(onFinished: (DshWebLaunchResult) -> Unit) {
        if (dshWebJob?.isActive == true) return
        dshWebState = DshWebUiState(DshWebPhase.STARTING)
        dshWebJob = viewModelScope.launch {
            val distribution = LinuxEnvironmentSettingsRepository.current(getApplication())
            val result = try {
                dshWebLauncher.launch(distribution.terminalEnvironment)
            } finally {
                if (dshWebState.phase == DshWebPhase.STARTING) dshWebState = DshWebUiState(DshWebPhase.READY)
            }
            dshWebState = when (result) {
                is DshWebLaunchResult.Opened -> DshWebUiState(DshWebPhase.RUNNING)
                is DshWebLaunchResult.Failed -> DshWebUiState(DshWebPhase.FAILED, result.code)
            }
            onFinished(result)
        }
    }

    fun stopDshWeb() {
        val preparation = dshWebJob
        preparation?.cancel()
        dshWebJob = viewModelScope.launch {
            preparation?.join()
            val distribution = LinuxEnvironmentSettingsRepository.current(getApplication())
            val status = dshWebLauncher.status(distribution.terminalEnvironment)
            val stopped = if (status.taskId == null) status.code == null
                else dshWebLauncher.stop(distribution.terminalEnvironment)
            dshWebState = if (stopped) DshWebUiState(DshWebPhase.READY)
                else DshWebUiState(DshWebPhase.FAILED, status.code ?: "STOP_FAILED")
        }
    }
}
