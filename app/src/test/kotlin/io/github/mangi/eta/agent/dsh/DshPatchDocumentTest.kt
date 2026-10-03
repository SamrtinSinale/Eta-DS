package io.github.mangi.eta.agent.dsh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.yaml.snakeyaml.Yaml

/**
 * `DshPatchDocument` 的纯文本单测：不碰文件系统，也不需要 Robolectric。
 *
 * 这里用 snakeyaml 只做一件事：**证明改完之后还是一份能读的 YAML**（它就是为此进的测试依赖）。
 * 注意不能说"用 snakeyaml 解析补丁文件是可行的"—— 反过来才是真的：真文件里的
 * `disabled: !!js "…"` 会直接把它顶掉（见 `snakeyamlRejectsTheExpressionForm`），
 * 所以生产代码走的是逐行改写，不是 YAML 库。
 *
 * 下面那份 [ACP_APP_PATCH] 是 `@deepseek-ai/dsh-acp-app/cordis.patch.yml` 的**逐字**内容
 * （注释与 `>-` 折叠标量都在），用它做夹具就是为了钉住"其余字节原样"这条。
 */
class DshPatchDocumentTest {

    private fun yaml(text: String): Any? = Yaml().load<Any>(text)

    @Suppress("UNCHECKED_CAST")
    private fun yamlEntries(text: String): List<Map<String, Any?>> = yaml(text) as List<Map<String, Any?>>

    @Test
    fun listsFlatAndNestedRowsInDocumentOrder() {
        val rows = DshPatchDocument.parse(ACP_APP_PATCH).rows
        assertEquals(
            listOf(
                "system-prompt" to null,
                "session-title-llm" to null,
                "acp-app-startup" to "@deepseek-ai/dsh-acp-app",
                "acp" to "@deepseek-ai/dsh-acp",
                "hmr" to null,
            ),
            rows.map { it.patchId to it.moduleName },
        )
        assertEquals(
            listOf(
                DshRowState.ENABLED,
                DshRowState.DISABLED,
                DshRowState.ENABLED,
                DshRowState.ENABLED,
                DshRowState.DISABLED,
            ),
            rows.map { it.state },
        )
    }

    @Test
    fun insertGroupIsNotARowButItsMembersAre() {
        val document = DshPatchDocument.parse(ACP_APP_PATCH)
        assertNull("`insert` 条目只是分组，它的 id 不该出现在行里", document.rejection)
        assertTrue(
            "嵌套行必须被列出来（官方列插件时也是 flatten 到这一层）",
            document.rows.any { it.patchId == "acp-app-startup" },
        )
        assertTrue("分组本身不该被当成插件", document.rows.none { it.patchId == "insert" })
    }

    @Test
    fun disablingInsertsTheKeyAndKeepsEveryOtherByte() {
        val edit = DshPatchDocument.parse(ACP_APP_PATCH)
            .withDisabled("system-prompt", moduleName = null, disabled = true)
        val expected = ACP_APP_PATCH.replace(
            "- id: system-prompt\n",
            "- id: system-prompt\n  disabled: true\n",
        )
        assertEquals(DshPatchEdit.Changed(expected), edit)
    }

    @Test
    fun enablingRewritesTheValueAndKeepsTheRest() {
        val edit = DshPatchDocument.parse(ACP_APP_PATCH)
            .withDisabled("session-title-llm", moduleName = null, disabled = false)
        val expected = ACP_APP_PATCH.replace(
            "- id: session-title-llm\n  disabled: true\n",
            "- id: session-title-llm\n  disabled: false\n",
        )
        assertEquals(DshPatchEdit.Changed(expected), edit)
    }

    @Test
    fun editingKeepsFoldedScalarsCommentsAndNestedBlocks() {
        val changed = edited(ACP_APP_PATCH, "system-prompt", null, disabled = true)
        assertTrue("开头的注释必须还在", changed.startsWith("# The automation-only ACP application"))
        assertTrue("折叠标量不能被摊平", changed.contains("    personaPrefix: >-\n      You are a coding agent"))
        assertTrue("insert 块必须原样", changed.contains("      inject: [acpAppStartup]"))
        assertTrue("末尾的 hmr 行必须原样", changed.endsWith("- id: hmr\n  disabled: true\n"))
    }

    @Test
    fun editedDocumentIsStillValidYaml() {
        val changed = edited(ACP_APP_PATCH, "system-prompt", null, disabled = true)
        val entries = yamlEntries(changed)
        assertEquals(4, entries.size)
        assertEquals(true, entries.first { it["id"] == "system-prompt" }["disabled"])
        assertEquals(
            "改一行不能让别的行变样",
            true,
            entries.first { it["id"] == "session-title-llm" }["disabled"],
        )
    }

    @Test
    fun alreadyInTheWantedStateWritesNothing() {
        assertEquals(
            DshPatchEdit.Unchanged,
            DshPatchDocument.parse(ACP_APP_PATCH).withDisabled("hmr", moduleName = null, disabled = true),
        )
    }

    @Test
    fun keepsTheTrailingCommentOfTheEditedLine() {
        val edit = DshPatchDocument.parse("- id: hmr\n  disabled: true  # 我自己加的\n")
            .withDisabled("hmr", moduleName = null, disabled = false)
        assertEquals(DshPatchEdit.Changed("- id: hmr\n  disabled: false  # 我自己加的\n"), edit)
    }

    @Test
    fun lastMatchingRowWins() {
        val text = "- id: dup\n  disabled: true\n\n- id: dup\n  disabled: true\n"
        val edit = DshPatchDocument.parse(text).withDisabled("dup", moduleName = null, disabled = false)
        val changed = (edit as DshPatchEdit.Changed).text
        assertEquals(
            "官方用 findLast：后写的那条才是被改的",
            listOf(DshRowState.DISABLED, DshRowState.ENABLED),
            DshPatchDocument.parse(changed).rows.map { it.state },
        )
    }

    @Test
    fun nameMismatchAppendsAnOverrideInstead() {
        val text = "- id: x\n  name: '@scope/a'\n"
        val changed = edited(text, "x", "@scope/b", disabled = true)
        assertEquals(
            "模块名对不上就不是同一行，于是追加一条覆盖行",
            listOf(DshRowState.ENABLED, DshRowState.DISABLED),
            DshPatchDocument.parse(changed).rows.map { it.state },
        )
        assertTrue("覆盖行排在最后才有意义", changed.endsWith("- id: x\n  disabled: true\n"))
    }

    @Test
    fun nameAbsenceStillMatches() {
        val changed = edited("- id: x\n", "x", "@scope/a", disabled = true)
        assertEquals(1, DshPatchDocument.parse(changed).rows.size)
        assertEquals(DshRowState.DISABLED, DshPatchDocument.parse(changed).rows.single().state)
    }

    @Test
    fun insertGroupIsNeverTheEditTarget() {
        val text = "- id: group\n  insert:\n    - id: inner\n"
        val changed = edited(text, "group", null, disabled = true)
        assertTrue("分组块一个字都不能动", changed.contains("- id: group\n  insert:\n    - id: inner\n"))
        assertTrue("只能另加一条同名覆盖行", changed.endsWith("- id: group\n  disabled: true\n"))
    }

    @Test
    fun appendingToAnEmptySequenceStaysValidYaml() {
        val edit = DshPatchDocument.parse(DshPatchDocument.EMPTY)
            .withDisabled("brand-new", moduleName = null, disabled = true)
        val changed = (edit as DshPatchEdit.Changed).text
        assertTrue("空的 `[]` 必须被替换掉，不能留下两个根节点", !changed.contains("[]"))
        assertEquals(listOf(mapOf("id" to "brand-new", "disabled" to true)), yaml(changed))
    }

    @Test
    fun appendingToAnEmptyDocumentStaysValidYaml() {
        val edit = DshPatchDocument.parse("").withDisabled("brand-new", moduleName = null, disabled = false)
        val changed = (edit as DshPatchEdit.Changed).text
        assertEquals("- id: brand-new\n  disabled: false\n", changed)
        assertEquals(listOf(mapOf("id" to "brand-new", "disabled" to false)), yaml(changed))
    }

    @Test
    fun appendingToACommentOnlyDocumentKeepsTheComment() {
        val edit = DshPatchDocument.parse("# 手写补丁\n").withDisabled("x", moduleName = null, disabled = true)
        val changed = (edit as DshPatchEdit.Changed).text
        assertTrue(changed.startsWith("# 手写补丁\n"))
        assertEquals(listOf(mapOf("id" to "x", "disabled" to true)), yaml(changed))
    }

    @Test
    fun appendingQuotesValuesYamlWouldResolveToSomethingElse() {
        // `id: true` 会被读成布尔、`id: a b` 会被读成字符串 —— 都必须加引号才还是那个 id。
        val changed = edited("", "true", null, disabled = true)
        assertTrue(changed.contains("- id: 'true'\n"))
        assertEquals("true", DshPatchDocument.parse(changed).rows.single().patchId)
        val spaced = edited("", "a b", null, disabled = false)
        assertTrue(spaced.contains("- id: 'a b'\n"))
        assertEquals("a b", DshPatchDocument.parse(spaced).rows.single().patchId)
    }

    @Test
    fun rejectsIdsThatCannotBeWritten() {
        val rejected = DshPatchDocument.parse(ACP_APP_PATCH).withDisabled("a\nb", moduleName = null, disabled = true)
        assertTrue(rejected is DshPatchEdit.Rejected)
    }

    @Test
    fun indentedRootSequenceIsStillEditable() {
        // 根序列整体缩进也是合法 YAML，追加时必须对齐它，否则一个文档里会出现两种缩进。
        assertEquals("  - id: a\n    disabled: false\n", edited("  - id: a\n    disabled: true\n", "a", null, false))
        val appended = edited("  - id: a\n", "b", null, disabled = true)
        assertEquals("  - id: a\n  - id: b\n    disabled: true\n", appended)
        assertEquals(listOf("a", "b"), DshPatchDocument.parse(appended).rows.map { it.patchId })
        assertEquals(2, yamlEntries(appended).size)
    }

    @Test
    fun siblingKeyAfterAnInsertBlockIsNotMistakenForABrokenFile() {
        // `insert:` 之后接兄弟键是正当写法：只跟 innermost 条目比缩进就会把它误判成坏文件。
        val text = "- id: a\n  insert:\n    - id: inner\n  config:\n    x: 1\n"
        assertNull(DshPatchDocument.parse(text).rejection)
        assertEquals(listOf("inner"), DshPatchDocument.parse(text).rows.map { it.patchId })
    }

    @Test
    fun rejectsARootMapping() {
        assertTrue(DshPatchDocument.parse("key: value\n").rejection != null)
        assertTrue(DshPatchDocument.parse("{}\n").rejection != null)
        assertTrue(
            "序列外面混进映射，这份文件已经不合法了",
            DshPatchDocument.parse("- id: x\nfoo: bar\n").rejection != null,
        )
    }

    @Test
    fun rejectsFlowStyleAndCrlfAndTabs() {
        assertTrue(DshPatchDocument.parse("- {id: x}\n").rejection != null)
        assertTrue(DshPatchDocument.parse("[{id: x}]\n").rejection != null)
        assertTrue(DshPatchDocument.parse("- id: x\r\n").rejection != null)
        assertTrue(DshPatchDocument.parse("- id: x\n\tname: y\n").rejection != null)
        assertTrue(
            "既有 `[]` 又有条目，本来就不是能读的 YAML",
            DshPatchDocument.parse("[]\n- id: x\n").rejection != null,
        )
    }

    @Test
    fun rejectionMeansNothingIsWritten() {
        val rejected = DshPatchDocument.parse("key: value\n")
            .withDisabled("x", moduleName = null, disabled = true)
        assertTrue(rejected is DshPatchEdit.Rejected)
        assertTrue("拒绝必须带上给界面显示的原因", (rejected as DshPatchEdit.Rejected).reason.isNotBlank())
    }

    @Test
    fun expressionAndQuotedBooleansAreUnresolvedNotEnabled() {
        // 这一条是这次改写的起因：`!!js` 是 dsh 自己的扩展标签，snakeyaml 连读都读不了，
        // 所以"禁用状态"只能按字面判断，判不了就得说判不了。
        val expression = DshPatchDocument.parse(
            "- insert:\n    - id: plugin-manager\n      name: '@deepseek-ai/dsh-plugin-manager'\n" +
                "      disabled: !!js \"!ctx.get('profileContext')\"\n",
        )
        assertNull("带 !!js 的文件是**能读**的，别拒绝", expression.rejection)
        assertEquals(DshRowState.UNRESOLVED, expression.rows.single().state)

        assertEquals(
            DshRowState.UNRESOLVED,
            DshPatchDocument.parse("- id: x\n  disabled: \"true\"\n").rows.single().state,
        )
        assertEquals(
            "`yes` 不是布尔写法，不敢当 true",
            DshRowState.UNRESOLVED,
            DshPatchDocument.parse("- id: x\n  disabled: yes\n").rows.single().state,
        )
        assertEquals(
            "`disabled:` 下面挂着块，也不知道是什么",
            DshRowState.UNRESOLVED,
            DshPatchDocument.parse("- id: x\n  disabled:\n    a: 1\n").rows.single().state,
        )
    }

    @Test
    fun snakeyamlRejectsTheExpressionForm() {
        // 这条不是钉我们的代码，是钉**为什么不能用 YAML 库**：一旦哪天有人想换回 snakeyaml，
        // 这个用例会告诉他真补丁层过不去。
        val failure = runCatching { Yaml().load<Any>("- id: x\n  disabled: !!js \"!ctx.get('a')\"\n") }
        assertTrue("snakeyaml 2.4 处理不了 !!js：${failure.exceptionOrNull()}", failure.isFailure)
    }

    private fun edited(text: String, patchId: String, moduleName: String?, disabled: Boolean): String {
        val edit = DshPatchDocument.parse(text).withDisabled(patchId, moduleName, disabled)
        return (edit as DshPatchEdit.Changed).text
    }

    private companion object {
        /** `@deepseek-ai/dsh-acp-app/cordis.patch.yml` 的逐字内容（含注释与折叠标量）。 */
        val ACP_APP_PATCH = """
            # The automation-only ACP application over dsh-base. Stdout belongs to ACP.

            - id: system-prompt
              config:
                personaSuffix: Your working directory is {{cwd}}.
                personaPrefix: >-
                  You are a coding agent powered by the {{model}} model.

            - id: session-title-llm
              disabled: true

            - insert:
                - id: acp-app-startup
                  name: '@deepseek-ai/dsh-acp-app'

                - id: acp
                  name: '@deepseek-ai/dsh-acp'
                  inject: [acpAppStartup]
                  config:
                    provider: deepseek-official
                    model: deepseek-v4-flash

            - id: hmr
              disabled: true
        """.trimIndent() + "\n"
    }
}
