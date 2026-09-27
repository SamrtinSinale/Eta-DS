package io.github.mangi.eta.ui.app

import android.content.Context
import io.github.mangi.eta.R

internal enum class DshWebPhase { CHECKING, NOT_INSTALLED, READY, STARTING, RUNNING, FAILED }

internal data class DshWebUiState(
    val phase: DshWebPhase = DshWebPhase.CHECKING,
    val errorCode: String? = null,
) {
    val canStop: Boolean get() = phase == DshWebPhase.STARTING || phase == DshWebPhase.RUNNING

    fun actionLabel(context: Context): String = context.getString(when (phase) {
        DshWebPhase.CHECKING -> R.string.capability_dsh_checking
        DshWebPhase.NOT_INSTALLED -> R.string.capability_dsh_install
        DshWebPhase.READY -> R.string.action_launch_dsh_web
        DshWebPhase.STARTING -> R.string.capability_dsh_preparing
        DshWebPhase.RUNNING -> R.string.capability_dsh_open
        DshWebPhase.FAILED -> R.string.capability_dsh_retry
    })
}

internal fun DshWebLaunchResult.Failed.message(context: Context): String = context.getString(when (code) {
    "ROOT_REQUIRED" -> R.string.capability_dsh_root_required
    "BACKGROUND_START_NOT_ALLOWED" -> R.string.capability_background_failed
    "DSH_EXITED", "PROCESS_EXITED" -> R.string.capability_dsh_exited
    "LINUX_ENVIRONMENT_NOT_READY", "PROFILE_NOT_INSTALLED" -> R.string.capability_dsh_not_installed
    "PROOT_UNAVAILABLE" -> R.string.capability_dsh_proot_unavailable
    "LOGS_UNAVAILABLE" -> R.string.capability_dsh_logs_unavailable
    "URL_TIMEOUT" -> R.string.linux_dsh_web_failed_url
    "BROWSER_UNAVAILABLE" -> R.string.linux_dsh_web_failed_browser
    else -> R.string.linux_dsh_web_failed_start
})
