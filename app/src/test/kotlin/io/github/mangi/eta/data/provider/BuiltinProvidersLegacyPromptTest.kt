package io.github.mangi.eta.data.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 改名遗留：`Eda` / `Eta` 时代的默认提示词仍留在已存的 provider 配置里（默认值只对新建的
 * provider 生效），于是用户在设置页看到的是那句旧文本。迁移只认"**恰好**等于旧默认值"的那些，
 * 用户改过的一律不动。
 *
 * 这条也是 3.0.6.x 期间"发出去的东西没人真跑过"那类问题的一小块，所以用真实历史文本做输入。
 */
class BuiltinProvidersLegacyPromptTest {

    private val edaDefault =
        "你是 Eda，运行在 Android 设备上的 AI 助手。你可以回答问题、与用户交流，也可以通过当前可用的工具了解设备情况并执行操作。回答使用用户的语言，简洁、直接、自然。"
    private val hetaDefault =
        "你是 Heta，运行在 Android 设备上的 AI 助手。你可以回答问题、与用户交流，也可以通过当前可用的工具了解设备情况并执行操作。回答使用用户的语言，简洁、直接、自然。"
    private val etaDefault =
        "你是 Eta，运行在 Android 设备上的 AI 助手。你可以回答问题、与用户交流，也可以通过当前可用的工具了解设备情况并执行操作。回答使用用户的语言，简洁、直接、自然。"

    @Test
    fun legacyDefaultsAreUpgradedToTheCurrentOne() {
        assertEquals(
            BuiltinProviders.DEFAULT_SYSTEM_PROMPT,
            BuiltinProviders.migratedSystemPrompt(edaDefault),
        )
        assertEquals(
            BuiltinProviders.DEFAULT_SYSTEM_PROMPT,
            BuiltinProviders.migratedSystemPrompt(etaDefault),
        )
        assertEquals(
            "两端空白不影响判定",
            BuiltinProviders.DEFAULT_SYSTEM_PROMPT,
            BuiltinProviders.migratedSystemPrompt("  $edaDefault\n"),
        )
    }

    /** Heta 时代那版默认人格，现在也是"旧默认值"，要被迁移到 dsh 版。 */
    @Test
    fun hetaDefaultIsAlsoMigratedToTheDshOne() {
        assertEquals(
            BuiltinProviders.DEFAULT_SYSTEM_PROMPT,
            BuiltinProviders.migratedSystemPrompt(hetaDefault),
        )
    }

    /**
     * 3.0.6.43 那版默认值（自称 "dsh 编码助手"）也要被断言。
     *
     * 它是**上一版**默认值、只活了一个版本、文本还是三段拼接 —— 最容易抄错，而设备上恰好有一批
     * provider 停在它上面。只断言集合大小、不断言内容的话，抄错了测试照样全绿。
     */
    @Test
    fun theDshVersionDefaultIsAlsoMigrated() {
        val dshDefault =
            "你是 dsh 编码助手（DeepSeek Harness），运行在 Android 设备上的 Heta 客户端中。" +
                "用户询问你的身份时说明你是 dsh；你可以调用 Heta 提供的手机能力（设备控制、终端/Linux 环境、浏览器）完成任务。" +
                "回答使用用户的语言，简洁、直接、自然。"
        assertEquals(
            BuiltinProviders.DEFAULT_SYSTEM_PROMPT,
            BuiltinProviders.migratedSystemPrompt(dshDefault),
        )
    }

    @Test
    fun customizedPromptsAreNeverTouched() {
        assertNull(BuiltinProviders.migratedSystemPrompt(BuiltinProviders.DEFAULT_SYSTEM_PROMPT))
        assertNull(BuiltinProviders.migratedSystemPrompt("你是 Heta，说话简短一点。"))
        assertNull(
            "只是加了半句话也算改过，不动",
            BuiltinProviders.migratedSystemPrompt(edaDefault + "另外，多说细节。"),
        )
    }

    @Test
    fun blankPromptsStayBlank() {
        assertNull(BuiltinProviders.migratedSystemPrompt(null))
        assertNull(BuiltinProviders.migratedSystemPrompt(""))
        assertNull(BuiltinProviders.migratedSystemPrompt("   "))
    }

    /**
     * 最老的那一版（`feb618c` 的"手机 Agent"）也要被断言。
     *
     * 这三个字符串是从 git 历史里抄来的，**最容易抄错**的就是不常出现的那条；只在集合里、
     * 不在测试里，等于没验。
     */
    @Test
    fun theOldestLegacyDefaultIsAlsoMigrated() {
        val oldest = "你是运行在 Android 设备上的手机 Agent。回答要简洁、直接，并保留必要的操作上下文。"

        assertEquals(
            BuiltinProviders.DEFAULT_SYSTEM_PROMPT,
            BuiltinProviders.migratedSystemPrompt(oldest),
        )
    }

    /**
     * 集合大小与"当前默认取历史最后一条"钉死。
     *
     * 这条防两件事：有人默默删掉一条历史值（迁移就漏了），以及有人改默认值时忘了 append ——
     * 后者会让**刚被替换掉的那份**变成不在集合里的滞留文本，Eda → Heta 那次就是这么来的。
     */
    @Test
    fun historyListAndDefaultsStayConsistent() {
        assertEquals(5, BuiltinProviders.LEGACY_DEFAULT_SYSTEM_PROMPTS.size)
        assertEquals(
            BuiltinProviders.DEFAULT_SYSTEM_PROMPT,
            BuiltinProviders.DEFAULT_SYSTEM_PROMPT_HISTORY.last(),
        )
        assertNull(
            "当前默认不该出现在旧值集合里",
            BuiltinProviders.LEGACY_DEFAULT_SYSTEM_PROMPTS
                .firstOrNull { it == BuiltinProviders.DEFAULT_SYSTEM_PROMPT },
        )
    }
}
