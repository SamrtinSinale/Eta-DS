package io.github.mangi.eta.agent.dsh

import io.github.mangi.eta.agent.model.AgentModelClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * dsh 原生会话的续接判定。
 *
 * 回归背景：App 早先每轮都 `session/new`，再把历史摘要塞进 prompt（实测每轮重放 9 万字符，
 * 且摘要只覆盖最近若干条）。dsh 自己会持久化会话、ACP 也有 `session/resume`，所以第二回合
 * 起应当续接；但只有"历史仍是建会话时那段前缀"才安全——编辑／重新生成会让历史分叉，
 * 换模型会让旧会话的配置失效，都必须重新开会话。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DshAcpSessionStateTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val model = "cn:deepseek-v4.1-flash"
    private val route = "deepseek-official"

    @Test
    fun fingerprintIsStableAndSensitiveToIdentity() {
        val history = conversation(turns = 3)
        val baseline = DshAcpSessionStateCodec.fingerprint(history, history.size)
        assertEquals(baseline, DshAcpSessionStateCodec.fingerprint(history, history.size))

        // 只算前缀，后面的消息不影响。
        assertEquals(baseline, DshAcpSessionStateCodec.fingerprint(history + message("user", "新的"), history.size))

        // 换了消息 id（编辑历史会重排/换 id）就要判定为分叉。
        val renamed = history.toMutableList().also { it[1] = it[1].copy(messageId = "另一个 id") }
        assertFalse(baseline == DshAcpSessionStateCodec.fingerprint(renamed, renamed.size))

        // 纯内容变化不参与指纹：同一条消息在 App 侧可能因图片编码有不同文本表示，
        // 掺进内容会让 resume 永远对不上。
        val retexted = history.toMutableList().also { it[1] = it[1].copy(content = "改过的内容") }
        assertEquals(baseline, DshAcpSessionStateCodec.fingerprint(retexted, retexted.size))
    }

    @Test
    fun fingerprintOfEmptyPrefixIsNotEmpty() {
        val empty = DshAcpSessionStateCodec.fingerprint(emptyList(), 0)
        assertTrue(empty.isNotBlank())
        assertEquals(empty, DshAcpSessionStateCodec.fingerprint(conversation(turns = 2), 0))
    }

    @Test
    fun resumeFollowsTheHistoryThroughSeveralTurns() {
        // 第一回合：历史为空，建会话。
        var history = emptyList<AgentModelClient.ConversationMessage>()
        var stored = persisted(sessionId = "dsh-1", history = history)

        // 第二回合：App 把第一轮的提示、回复与工具结果追加进历史。
        history = history + prompt("第一轮") + assistant("回答一") + tool("结果一")
        assertTrue(canResume(stored, history))

        // 第三回合：继续增长，仍然可续接。
        stored = persisted("dsh-1", history)
        history = history + prompt("第二轮") + assistant("回答二")
        assertTrue(canResume(stored, history))
    }

    @Test
    fun resumeIsRefusedWhenHistoryDiverges() {
        val original = conversation(turns = 4)
        val stored = persisted("dsh-1", original)

        // 编辑历史中间的一条：id 变了，旧会话不能再用。
        val edited = original.toMutableList().also { it[2] = it[2].copy(messageId = "被编辑过") }
        assertFalse(canResume(stored, edited))

        // 重新生成：历史被截短到分叉点。
        assertFalse(canResume(stored, original.drop(2)))

        // 换模型：旧会话里记的是旧模型，不能接着跑。
        assertFalse(DshAcpSessionStateCodec.canResume(stored, original, "另一个模型"))
        // 路由变化不参与续接判定：那条守卫曾是恒为假的死代码（见 canResume 的注释）。

        // 会话 id 空（从未建过）时也不能续接。
        assertFalse(canResume(persisted("", original), original))
    }

    @Test
    fun storedPrefixMatchesTheNextTurnRequest() {
        // 真实时序：每次 run 成功后才把"本轮请求的历史"写成前缀，下一轮请求的历史 =
        // 上一轮历史 + 上一轮提示 + 上一轮产出。这条用例锁住"存的前缀和校验的前缀是同一段"，
        // 曾经把 count 记成 history+1+transcript，导致 resume 永远对不上、静默退回每轮重放。
        var history = emptyList<AgentModelClient.ConversationMessage>()
        var stored: DshAcpSessionState? = null
        repeat(4) { turn ->
            if (stored != null) {
                assertTrue("turn=${turn + 1}", canResume(requireNotNull(stored), history))
            }
            stored = persisted("dsh-1", history)
            history = history + prompt("第 $turn 轮") + assistant("回答 $turn") + tool("结果 $turn")
        }
    }

    @Test
    fun stateRoundTripsThroughTheStore() {
        val store = DshAcpSessionStore(temporaryFolder.newFolder("sessions"))
        assertNull(store.load("conversation-a"))

        val history = conversation(turns = 2)
        store.save(
            "conversation-a",
            DshAcpSessionState(
                sessionId = "dsh-abc",
                historyCount = history.size,
                historyFingerprint = DshAcpSessionStateCodec.fingerprint(history, history.size),
                model = model,
                providerRoute = route,
            ),
        )

        val loaded = store.load("conversation-a")
        assertEquals("dsh-abc", loaded?.sessionId)
        assertEquals(history.size, loaded?.historyCount)
        assertEquals(DshAcpSessionStateCodec.fingerprint(history, history.size), loaded?.historyFingerprint)
        assertTrue(canResume(requireNotNull(loaded), history))

        // 不同会话互不干扰。
        assertNull(store.load("conversation-b"))

        store.remove("conversation-a")
        assertNull(store.load("conversation-a"))
    }

    @Test
    fun corruptStateIsTreatedAsNoSession() {
        val directory = temporaryFolder.newFolder("sessions")
        val store = DshAcpSessionStore(directory)
        store.save(
            "conversation-a",
            DshAcpSessionState(
                sessionId = "dsh-abc",
                historyCount = 0,
                historyFingerprint = DshAcpSessionStateCodec.fingerprint(emptyList(), 0),
                model = model,
                providerRoute = route,
            ),
        )
        directory.listFiles()?.forEach { it.writeText("{ 不是 json") }
        assertNull(store.load("conversation-a"))
    }

    @Test
    fun storeDoesNotKeepUnboundedFiles() {
        val directory = temporaryFolder.newFolder("sessions")
        val store = DshAcpSessionStore(directory)
        repeat(140) { index ->
            store.save(
                "conversation-$index",
                DshAcpSessionState(
                    sessionId = "dsh-$index",
                    historyCount = 0,
                    historyFingerprint = "f",
                    model = model,
                    providerRoute = route,
                ),
            )
        }
        val kept = directory.listFiles { file -> file.name.endsWith(".json") }?.size ?: 0
        assertTrue("kept=$kept", kept <= 128)
    }

    private fun persisted(
        sessionId: String,
        history: List<AgentModelClient.ConversationMessage>,
    ) = DshAcpSessionState(
        sessionId = sessionId,
        historyCount = history.size,
        historyFingerprint = DshAcpSessionStateCodec.fingerprint(history, history.size),
        model = model,
        providerRoute = route,
    )

    private fun canResume(
        persisted: DshAcpSessionState,
        history: List<AgentModelClient.ConversationMessage>,
    ) = DshAcpSessionStateCodec.canResume(persisted, history, model)

    private fun conversation(turns: Int): List<AgentModelClient.ConversationMessage> =
        buildList {
            repeat(turns) { index ->
                add(prompt("第 $index 轮"))
                add(assistant("回答 $index"))
                add(tool("结果 $index"))
            }
        }

    private fun prompt(text: String) =
        AgentModelClient.buildUserHistoryMessage(text, emptyList()).copy(messageId = "user-$text")

    private fun assistant(text: String) =
        AgentModelClient.ConversationMessage(role = "assistant", content = text, messageId = "assistant-$text")

    private fun tool(text: String) =
        AgentModelClient.ConversationMessage(
            role = "tool",
            content = text,
            toolCallId = "call-$text",
            messageId = "tool-$text",
        )

    private fun message(role: String, content: String) =
        AgentModelClient.ConversationMessage(role = role, content = content, messageId = "id-$content")
}
