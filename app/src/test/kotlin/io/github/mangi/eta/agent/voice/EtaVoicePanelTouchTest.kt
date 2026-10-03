package io.github.mangi.eta.agent.voice

import android.app.Application
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.ui.app.AgentAppTheme
import io.github.mangi.eta.ui.model.AgentMessageUi
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, qualifiers = "w411dp-h896dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EtaVoicePanelTouchTest {
    @Test
    fun handleCanDragDownAfterEmptyEntryReceivesReply() = withPanel { panel ->
        panel.showReply()
        val before = panel.handleBounds()
        panel.dragBy(200f)
        val after = panel.handleBounds()
        assertTrue(
            "收到回复后把手应能向下拖动：before=$before after=$after",
            after.top > before.top + panel.dp(30f),
        )
        assertEquals(0, panel.opened)
        assertEquals(0, panel.closed)
    }

    @Test
    fun handleCanHandoffAfterReplyCompletesWithoutChangingBaseHeight() = withPanel { panel ->
        panel.showReply(processing = true)
        panel.state.value = panel.state.value.copy(phase = EtaVoicePhase.READY)
        panel.advance()
        panel.dragBy(-80f)
        assertEquals(1, panel.opened)
        assertEquals(0, panel.closed)
    }

    @Test
    fun cancelledHandleDragDoesNotHandoff() = withPanel { panel ->
        panel.showReply()
        panel.dragBy(-80f, cancel = true)
        assertEquals(0, panel.opened)
        assertEquals(0, panel.closed)
    }

    private fun withPanel(block: (Panel) -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val panel = Panel(controller.get())
        try {
            panel.advance()
            block(panel)
        } finally {
            panel.close()
            controller.pause().stop().destroy()
        }
    }

    private class Panel(val activity: ComponentActivity) {
        val state = mutableStateOf(EtaVoiceUiState())
        var opened = 0
        var closed = 0
        private val frameClock = BroadcastFrameClock()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frameClock)
        private val recomposer = Recomposer(scope.coroutineContext)

        init {
            scope.launch { recomposer.runRecomposeAndApplyChanges() }
            val view = ComposeView(activity)
            view.setParentCompositionContext(recomposer)
            view.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    CompositionLocalProvider(LocalSquircleEnabled provides false) {
                        EtaVoicePanel(
                            speechLevel = { 0f },
                            state = state.value,
                            input = "",
                            speech = EtaSpeechState(),
                            onMicrophone = {},
                            onFinishSpeech = {},
                            onDownloadModel = {},
                            onOpenSpeechSettings = {},
                            onKeyboard = {},
                            inputFocusRequestKey = -1,
                            canOpenConversation = state.value.phase == EtaVoicePhase.READY &&
                                state.value.messages.isNotEmpty(),
                            handoffRunning = false,
                            exitRequested = false,
                            onInputChange = {},
                            onSuggestionClick = {},
                            onSubmit = {},
                            onStop = {},
                            onClose = { closed++ },
                            onOpenConversation = { opened++ },
                        )
                    }
                }
            }
            activity.setContentView(view)
        }

        fun close() {
            recomposer.cancel()
            scope.cancel()
        }

        fun showReply(processing: Boolean = false) {
            state.value = EtaVoiceUiState(
                messages = listOf(
                    AgentMessageUi(
                        id = "reply",
                        content = (1..70).joinToString("\n") { "Eta 测试回复 $it" },
                        renderMarkdown = false,
                    ),
                ),
                phase = if (processing) EtaVoicePhase.PROCESSING else EtaVoicePhase.READY,
            )
            advance()
        }

        fun advance() {
            // 手动驱动重组帧和布局，使无真实显示器的触摸测试不依赖后台线程时序。
            repeat(180) {
                Snapshot.sendApplyNotifications()
                shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
                frameClock.sendFrame(SystemClock.uptimeMillis() * 1_000_000L)
                root().measureAndLayoutForTest()
            }
        }

        fun dp(value: Float): Float = value * root().density.density

        fun handleBounds(): Rect = findHandle(root().semanticsOwner.unmergedRootSemanticsNode)?.boundsInRoot
            ?: error("未找到顶部把手")

        fun dragBy(distanceDp: Float, cancel: Boolean = false) {
            val view = root().view
            val bounds = handleBounds()
            val node = findHandle(root().semanticsOwner.unmergedRootSemanticsNode)!!
            assertTrue(
                "把手应已完成布局：bounds=$bounds size=${node.size} " +
                    "position=${node.positionInRoot} view=${view.width}x${view.height}",
                bounds.width > 0 && bounds.height > 0,
            )
            val start = bounds.center
            val downTime = SystemClock.uptimeMillis()
            fun send(action: Int, y: Float) {
                val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, start.x, y, 0)
                try {
                    view.dispatchTouchEvent(event)
                } finally {
                    event.recycle()
                }
                shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            }
            send(MotionEvent.ACTION_DOWN, start.y)
            repeat(10) { index ->
                send(MotionEvent.ACTION_MOVE, start.y + dp(distanceDp) * (index + 1) / 10f)
            }
            send(if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, start.y + dp(distanceDp))
            advance()
        }

        private fun root(): ViewRootForTest = findRoot(activity.window.decorView) ?: error("未找到 Compose Root")

        private fun findRoot(view: View): ViewRootForTest? {
            if (view is ViewRootForTest) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) {
                findRoot(view.getChildAt(index))?.let { return it }
            }
            return null
        }

        private fun findHandle(node: SemanticsNode): SemanticsNode? {
            if (node.config.getOrNull(SemanticsProperties.TestTag) == "eta_assistant_drag_handle") return node
            for (child in node.children) findHandle(child)?.let { return it }
            return null
        }
    }
}
