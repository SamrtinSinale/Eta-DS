package io.github.mangi.eta.ui.app

import io.github.mangi.eta.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DshWebUiStateTest {
    @Test
    fun preparationAndRunningBothOfferStopButIdleAndFailuresDoNot() {
        assertTrue(DshWebUiState(DshWebPhase.STARTING).canStop)
        assertTrue(DshWebUiState(DshWebPhase.RUNNING).canStop)
        assertFalse(DshWebUiState(DshWebPhase.NOT_INSTALLED).canStop)
        assertFalse(DshWebUiState(DshWebPhase.FAILED).canStop)
    }

    @Test
    fun permissionAndProcessFailuresHaveTheirOwnRecoveryMessage() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals(context.getString(R.string.capability_dsh_root_required),
            DshWebLaunchResult.Failed("ROOT_REQUIRED").message(context))
        assertEquals(context.getString(R.string.capability_background_failed),
            DshWebLaunchResult.Failed("BACKGROUND_START_NOT_ALLOWED").message(context))
        assertEquals(context.getString(R.string.capability_dsh_exited),
            DshWebLaunchResult.Failed("DSH_EXITED").message(context))
    }
}
