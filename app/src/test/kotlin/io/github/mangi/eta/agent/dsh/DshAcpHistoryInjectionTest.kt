package io.github.mangi.eta.agent.dsh

import io.github.mangi.eta.agent.model.AgentModelClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 历史注入预算与收集规则。
 *
 * 回归背景：预算是固定 6000 字符时，长会话每轮只注入最近十来条被截断的消息，
 * 上下文占用永远停在窗口的个位数百分比（实测 33k / 1M ≈ 3%），而 dsh 侧没有
 * 读取完整会话的工具，模型看不到对话。
 */
class DshAcpHistoryInjectionTest {

    @Test
    fun budgetIsAShareOfTheModelWindow() {
        assertEquals(150_000, DshAcpRuntime.historyBudgetChars(1_000_000))
        assertEquals(38_400, DshAcpRuntime.historyBudgetChars(256_000))
        assertEquals(19_200, DshAcpRuntime.historyBudgetChars(128_000))
        assertEquals(9_600, DshAcpRuntime.historyBudgetChars(64_000))
    }

    @Test
    fun budgetFallsBackForUnknownWindowAndClampsAtBothEnds() {
        assertEquals(19_200, DshAcpRuntime.historyBudgetChars(null))
        assertEquals(19_200, DshAcpRuntime.historyBudgetChars(0))
        assertEquals(19_200, DshAcpRuntime.historyBudgetChars(-1))
        // 小窗口不能被历史挤爆：下限就是原来的固定值。
        assertEquals(6_000, DshAcpRuntime.historyBudgetChars(8_000))
        // 超大窗口一次塞进过多文本没有意义：上限 150k 字符。
        assertEquals(150_000, DshAcpRuntime.historyBudgetChars(4_000_000))
        assertEquals(150_000, DshAcpRuntime.historyBudgetChars(Int.MAX_VALUE))
    }

    @Test
    fun budgetNeverDropsBelowTheOldFlatCap() {
        for (window in listOf(1, 1_000, 8_000, 32_000, 128_000, 1_000_000, Int.MAX_VALUE)) {
            assertTrue(
                "window=$window",
                DshAcpRuntime.historyBudgetChars(window) >= 6_000,
            )
        }
    }

    @Test
    fun longConversationInjectsFarMoreThanTheFlatCap() {
        val history = conversation(turns = 120, charsPerMessage = 900)
        val old = DshAcpRuntime.collectHistoryLines(history, budgetChars = 6_000)
        val fixed = DshAcpRuntime.collectHistoryLines(
            history,
            budgetChars = DshAcpRuntime.historyBudgetChars(1_000_000),
        )
        assertTrue("old=${old.size} fixed=${fixed.size}", fixed.size > old.size * 5)
        assertTrue(fixed.sumOf { it.length } > 100_000)
    }

    @Test
    fun collectionKeepsTheNewestMessagesInChronologicalOrder() {
        val history = listOf(
            message("user", "第一条"),
            message("assistant", "第二条"),
            message("user", "第三条"),
        )
        val lines = DshAcpRuntime.collectHistoryLines(history, budgetChars = 100)
        assertEquals(listOf("用户：第一条", "你：第二条", "用户：第三条"), lines)
    }

    @Test
    fun collectionStopsWhenTheNextMessageDoesNotFit() {
        val history = listOf(
            message("user", "旧".repeat(3_000)),
            message("assistant", "中".repeat(3_000)),
            message("user", "新".repeat(3_000)),
        )
        // 单条上限放宽到不截断：默认的 2000 字符上限会把三条各压到 2000，
        // 合计 6008 仍小于预算，测不出"放不下就停"。
        val lines = DshAcpRuntime.collectHistoryLines(
            history,
            budgetChars = 6_500,
            maxCharsPerMessage = 4_000,
        )
        // 最新两条放得下（3003 + 3002 = 6005），第三条（更旧，3003）放不下就停：
        // 宁可少注入，也不拼出缺中间环节的一段。
        assertEquals(2, lines.size)
        assertTrue(lines.last().endsWith("新".repeat(3_000)))
        assertTrue(lines.first().startsWith("你："))
    }

    @Test
    fun collectionSkipsMessagesWithNothingToRender() {
        val history = listOf(
            message("user", "在吗"),
            AgentModelClient.ConversationMessage(role = "assistant"),
            message("assistant", "在"),
        )
        val lines = DshAcpRuntime.collectHistoryLines(history, budgetChars = 1_000)
        assertEquals(listOf("用户：在吗", "你：在"), lines)
    }

    @Test
    fun collectionTruncatesEachMessageAndCapsTheCount() {
        val history = conversation(turns = 40, charsPerMessage = 10_000)
        val lines = DshAcpRuntime.collectHistoryLines(
            history,
            budgetChars = 1_000_000,
            maxMessages = 12,
            maxCharsPerMessage = 2_000,
        )
        assertEquals(12, lines.size)
        for (line in lines) {
            assertTrue("line=${line.length}", line.length <= 2_000 + 8)
        }
    }

    @Test
    fun toolResultsKeepTheirRoleLabel() {
        val history = listOf(
            AgentModelClient.ConversationMessage(role = "assistant", toolCallsJson = toolCalls("mcp__eta__bash")),
            AgentModelClient.ConversationMessage(role = "tool", content = "ok"),
        )
        val lines = DshAcpRuntime.collectHistoryLines(history, budgetChars = 1_000)
        assertEquals(listOf("你调用了工具：bash", "工具结果：ok"), lines)
    }

    private fun conversation(turns: Int, charsPerMessage: Int): List<AgentModelClient.ConversationMessage> =
        buildList {
            repeat(turns) { index ->
                add(message("user", "问题 $index：" + "甲".repeat(charsPerMessage)))
                add(message("assistant", "回答 $index：" + "乙".repeat(charsPerMessage)))
                add(AgentModelClient.ConversationMessage(role = "tool", content = "结果 $index：" + "丙".repeat(charsPerMessage)))
            }
        }

    private fun message(role: String, content: String) =
        AgentModelClient.ConversationMessage(role = role, content = content)

    private fun toolCalls(name: String) =
        """[{"id":"call_1","type":"function","function":{"name":"$name","arguments":"{}"}}]"""
}
