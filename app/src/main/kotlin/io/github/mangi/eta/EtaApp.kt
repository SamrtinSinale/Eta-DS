package io.github.mangi.eta

import io.github.mangi.eta.agent.dsh.DshRuntimeInstaller
import io.github.mangi.eta.agent.terminal.LinuxEnvironmentPaths
import io.github.mangi.eta.agent.terminal.LinuxDistribution
import io.github.mangi.eta.core.StaleRetirementSweeper
import io.github.mangi.eta.agent.voice.SpeechOssUpload
import android.app.Application
import android.os.Handler
import android.os.Looper
import io.github.mangi.eta.agent.mcp.AgentToolServerHost
import io.github.mangi.eta.agent.skill.SkillRuntime
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.terminal.TerminalRuntime
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import io.github.mangi.eta.data.repository.AppearanceSettingsRepository
import io.github.mangi.eta.data.repository.McpServerRepository
import io.github.mangi.eta.data.repository.LinuxEnvironmentSettingsRepository
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.ui.app.PredictiveBackController
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CopyOnWriteArraySet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * 模块 UI 进程的 Application。
 *
 * 在进程启动时注册 [XposedServiceHelper] 监听器，框架会通过 XposedProvider 推送 binder，
 * 随后 UI 即可拿到 [XposedService] 写入 RemotePreferences，跨进程同步到各 hook 进程。
 *
 * UI 侧通过 [XposedService] 写入 RemotePreferences。
 */
class EtaApp : Application(), XposedServiceHelper.OnServiceListener {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    interface ServiceStateListener {
        fun onServiceStateChanged(service: XposedService?)
    }

    override fun onCreate() {
        super.onCreate()
        Prefs.initLocal(this)
        if (!AppProcessPolicy.shouldInitializeFullRuntime(Application.getProcessName(), packageName)) {
            return
        }
        TerminalRuntime.initialize(this)
        RootAccess.initialize(this)
        SettingsDataStore.init(this)
        val predictiveBackEnabled = runBlocking(Dispatchers.IO) {
            AppearanceSettingsRepository.settings().predictiveBackEnabled
        }
        PredictiveBackController.apply(applicationInfo, predictiveBackEnabled)
        AgentMemoryRepository.init(this)
        ProviderRepository.init(this)
        McpServerRepository.init(this)
        XposedServiceHelper.registerListener(this)
        applicationScope.launch {
            try {
                SpeechOssUpload(this@EtaApp).retryPending(this@EtaApp)
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                AndroidAgentLogger.warn("Eta speech cleanup unavailable: type=${error.javaClass.simpleName}")
            }
        }
        applicationScope.launch {
            LinuxEnvironmentSettingsRepository.initialize(this@EtaApp)
            runCatching {
                AgentToolServerHost.ensureStarted(this@EtaApp)
                io.github.mangi.eta.agent.dsh.DshRuntimeInstaller.ensureInstalled(this@EtaApp)
            }.onFailure { throwable ->
                AndroidAgentLogger.warn(
                    "Agent tool server start failed: type=${throwable.safeLogType()}"
                )
            }
            runCatching {
                // 让路残骸的回收：没有这一步，*.broken-* 就是看不见的磁盘增长（实测有 291MB 的）。
                // 扫多根：让路产物既有 filesDir 顶层的，也有 rootfs 深处的（opt/eta/...）。
                val retirementRoots = listOf(
                    filesDir,
                    LinuxEnvironmentPaths.rootfsDir(this@EtaApp, LinuxDistribution.DEBIAN),
                    LinuxEnvironmentPaths.rootfsDir(this@EtaApp, LinuxDistribution.ALPINE),
                )
                StaleRetirementSweeper.sweep(
                    filesDir = filesDir,
                    roots = retirementRoots,
                    purge = { target -> DshRuntimeInstaller.purgeAsRoot(target) },
                ) { unreleased, outcome ->
                    AndroidAgentLogger.info(
                        "让路残骸未释放（$outcome），等挂载消失或权限允许再收：${unreleased.absolutePath}"
                    )
                }
            }.onFailure { throwable ->
                AndroidAgentLogger.warn(
                    "Stale retirement sweep failed: type=${throwable.safeLogType()}"
                )
            }
            runCatching {
                // 改名遗留：已存的 provider 里那句 "你是 Eda/Eta，…" 还留着，升级成当前默认。
                ProviderRepository.migrateLegacySystemPrompts()
            }.onFailure { throwable ->
                AndroidAgentLogger.warn(
                    "Provider prompt migration failed: type=${throwable.safeLogType()}"
                )
            }
            runCatching {
                SkillRuntime.createIndexService(this@EtaApp).listInstalledSkills()
            }.onFailure { throwable ->
                AndroidAgentLogger.warn(
                    "Agent skill index prewarm failed: type=${throwable.safeLogType()}"
                )
            }
        }
    }

    override fun onServiceBind(service: XposedService) {
        serviceInstance = service
        Prefs.reconcileAgentPreferences(service)
        dispatch(service)
    }

    override fun onServiceDied(service: XposedService) {
        // 只有当前持有的 service 死亡时才清空并派发 null；
        // 多 framework 场景下死掉的可能是已被替换的旧实例，无需影响 UI。
        if (serviceInstance === service) {
            serviceInstance = null
            dispatch(null)
        }
    }

    companion object {
        @Volatile
        var serviceInstance: XposedService? = null
            private set

        private val listeners = CopyOnWriteArraySet<ServiceStateListener>()
        private val mainHandler = Handler(Looper.getMainLooper())

        fun addServiceStateListener(listener: ServiceStateListener, notifyImmediately: Boolean) {
            listeners.add(listener)
            if (notifyImmediately) {
                dispatchTo(listener, serviceInstance)
            }
        }

        fun removeServiceStateListener(listener: ServiceStateListener) {
            listeners.remove(listener)
        }

        private fun dispatch(service: XposedService?) {
            listeners.forEach { dispatchTo(it, service) }
        }

        private fun dispatchTo(listener: ServiceStateListener, service: XposedService?) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                listener.onServiceStateChanged(service)
            } else {
                mainHandler.post {
                    if (listeners.contains(listener)) {
                        listener.onServiceStateChanged(service)
                    }
                }
            }
        }
    }
}
