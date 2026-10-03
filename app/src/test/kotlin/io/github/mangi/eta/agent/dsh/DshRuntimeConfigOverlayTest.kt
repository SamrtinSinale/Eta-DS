package io.github.mangi.eta.agent.dsh

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.yaml.snakeyaml.Yaml

/**
 * 给 dsh 的 `--patch` 覆盖层写了什么。
 *
 * 回归背景：dsh 的 loader 对 `- id: X` / `config:` 是**整块替换**，不是按键合并。
 * 覆盖层里只写 `personaSuffix` 会把 dsh ACP profile 自带的 `personaPrefix`
 * （"You are a coding agent powered by the {{model}} model."）静默抹掉——
 * `dsh --profile acp --dump-config` 里那一行会直接消失，模型再也看不到自己是哪个模型。
 *
 * 所以这里把「两个键都得在」钉死：以后谁再往这个覆盖层里加插件配置，少写键就会红。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DshRuntimeConfigOverlayTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun config(
        skillsDirectory: String = "",
        model: String = "global:deepseek-v4.1-flash",
    ): DshRuntimeConfig {
        val rootfs = temporaryFolder.newFolder("dsh-runtime")
        return DshRuntimeConfig(
            rootfsPath = rootfs.absolutePath,
            providerRoute = "deepseek-official",
            model = model,
            apiKey = "sk-test",
            baseUrl = "https://example.invalid",
            skillsDirectory = skillsDirectory,
        )
    }

    private fun overlayOf(config: DshRuntimeConfig): String {
        // command() 会顺带把覆盖层写到 rootfs 里，返回的是 su 命令行；这里只关心副作用。
        config.command()
        return File(config.rootfsPath, "opt/dsh/heta-run-overlay.patch.yml").readText()
    }

    @Test
    fun overlayKeepsThePersonaPrefixAlongsideItsOwnSuffix() {
        val overlay = overlayOf(config())

        assertTrue(
            "personaPrefix 被整块替换吃掉了：\n$overlay",
            overlay.contains("personaPrefix: \"You are a coding agent powered by the {{model}} model.\""),
        )
        assertTrue("personaSuffix 没了：\n$overlay", overlay.contains("personaSuffix: |"))
        assertTrue("工作目录一行应该还在后缀里：\n$overlay", overlay.contains("Your working directory is {{cwd}}."))
    }

    @Test
    fun overlayCarriesTheDsh020ModelDefaultsInsteadOfTheOldCatalog() {
        val overlay = overlayOf(config())

        assertTrue("flash 的缓存友好更新语义丢了：\n$overlay", overlay.contains("systemPromptUpdate: in-history"))
        assertTrue("flash 的工具增量更新语义丢了：\n$overlay", overlay.contains("toolUpdate: addition-only"))
        assertTrue("v4-pro 的说明丢了：\n$overlay", overlay.contains("Stronger agentic coding, knowledge, and difficult reasoning"))
        assertFalse("旧的 v4-flash 仍在覆盖目录里：\n$overlay", overlay.contains("id: \"deepseek-v4-flash\"\n"))
        assertFalse("已经下线的 vision-exp 模型仍在覆盖目录里：\n$overlay", overlay.contains("deepseek-v4-flash-vision-exp"))
    }

    @Test
    fun overlayStillPinsTheModelAndTheRoute() {
        val overlay = overlayOf(config())

        assertTrue(overlay.contains("- id: acp\n"))
        assertTrue(overlay.contains("    provider: \"deepseek-official\"\n"))
        assertTrue(overlay.contains("    model: \"global:deepseek-v4.1-flash\"\n"))
    }

    @Test
    fun commandUsesExposeInternalsInsteadOfTheAndroidNativeAddon() {
        val command = config().command().joinToString(" ")

        assertTrue("Node 必须启用内部模块旁路：$command", command.contains("--expose-internals"))
        assertTrue("dsh 入口仍在 Node 参数之后：$command", command.contains("/opt/dsh/lib/bin.js"))
    }

    @Test
    fun overlayIndexesTheSkillsDirectoryIntoTheSuffix() {
        val skills = temporaryFolder.newFolder("skills")
        File(skills, "demo-skill").apply { mkdirs() }
        File(skills, "demo-skill/SKILL.md").writeText(
            """
            ---
            name: demo-skill
            description: 一句话说明这个技能干什么
            ---

            正文。
            """.trimIndent(),
        )

        val overlay = overlayOf(config(skillsDirectory = skills.absolutePath))

        assertTrue("技能索引没进后缀：\n$overlay", overlay.contains("- demo-skill：一句话说明这个技能干什么"))
    }

    @Test
    fun overlaySaysSoWhenTheSkillsDirectoryIsEmpty() {
        val overlay = overlayOf(config())

        assertTrue("空技能库也要有明确文案：\n$overlay", overlay.contains("当前技能库为空。"))
    }

    /**
     * 用真 YAML 解析器读覆盖层，返回 `llm-deepseek.config.models` 的条目。
     *
     * 回归背景：`DshBuiltinModelCatalog.YAML` 是 `trimIndent()` 的产物、**结尾没有换行**，
     * 而它后面无论是下一个模型条目还是 `- id: acp`，都会被 `append` 粘到
     * `contextWindow: 1000000` 那一行上：
     *
     *     contextWindow: 1000000      - id: "global:deepseek-v4.1-flash"
     *
     * dsh 用 js-yaml 读这份覆盖层，这一步直接抛
     * `YAMLException: bad indentation of a mapping entry`，进程在 `initialize` 回包前退出，
     * Heta 侧只看到 `protocol-eof`。以前的 `contains(...)` 断言对坏 YAML 完全免疫，
     * 所以这里必须真解析一遍。
     */
    private fun modelsOf(overlay: String): List<Map<*, *>> {
        val parsed: Any? = Yaml().load<Any>(overlay)
        assertTrue("覆盖层没解析成列表：\n$overlay", parsed is List<*>)
        val entries = (parsed as List<*>).filterIsInstance<Map<*, *>>()
        val llm = entries.firstOrNull { it["id"] == "llm-deepseek" }
        assertNotNull("覆盖层里没有 llm-deepseek：\n$overlay", llm)
        val models = (llm?.get("config") as? Map<*, *>)?.get("models")
        assertTrue("llm-deepseek.config.models 不是列表：\n$overlay", models is List<*>)
        return (models as List<*>).filterIsInstance<Map<*, *>>()
    }

    @Test
    fun overlayParsesAsYamlWithAnExtraModel() {
        val overlay = overlayOf(config())
        val models = modelsOf(overlay)

        assertEquals(
            "models 条目数 = 内置目录 + 自定义模型：\n$overlay",
            DshBuiltinModelCatalog.IDS.size + 1,
            models.size,
        )
        assertEquals(
            "自定义模型没进目录或顺序变了：\n$overlay",
            DshBuiltinModelCatalog.IDS + "global:deepseek-v4.1-flash",
            models.map { it["id"] },
        )
    }

    @Test
    fun extraModelCarriesTheSameUpdateSemanticsAsTheBuiltinOne() {
        val overlay = overlayOf(config())
        val extra = modelsOf(overlay).first { it["id"] == "global:deepseek-v4.1-flash" }

        // dsh 只在"有值"时才透传这两个键：省略 = 不声明，续接的旧会话拿不到 prompt/工具更新。
        assertEquals("systemPromptUpdate 丢了：\n$overlay", "in-history", extra["systemPromptUpdate"])
        assertEquals("toolUpdate 丢了：\n$overlay", "addition-only", extra["toolUpdate"])
    }

    @Test
    fun startupScriptPrunesAgedDshSessions() {
        val config = config()
        val command = config.command().joinToString(" ")

        // dsh 自己的会话文件没人回收，只能在启动时按年龄清。
        assertTrue(
            "启动脚本没有清理 dsh 会话目录：$command",
            command.contains("${config.rootfsPath}/root/.dsh/sessions"),
        )
        assertTrue("清理没带年龄条件：$command", command.contains("-mtime +14 -delete"))
        // 投影缓存比会话本体大一个数量级，漏了它等于只清了小头。
        assertTrue(
            "投影缓存没清：$command",
            command.contains("${config.rootfsPath}/root/.dsh/storages/session_projcache"),
        )
        // 只删文件会留下一树空壳。
        assertTrue("没收回空目录：$command", command.contains("-type d -empty -delete"))
    }

    @Test
    fun commandKeepsCredentialsOutOfArgv() {
        val config = config()
        val command = config.command().joinToString(" ")

        // `su -c <script>` 的整段脚本就是 argv，凭据写进去等于给 `ps` 看。
        assertFalse("API Key 出现在启动命令里：$command", command.contains("sk-test"))
        assertFalse("网关地址出现在启动命令里：$command", command.contains("example.invalid"))
        // source 发生在 exec chroot 之前，所以这里必须是宿主路径。
        assertTrue(
            "凭据文件没按宿主路径 source：$command",
            command.contains("${config.rootfsPath}/opt/dsh/heta-run-env.sh"),
        )
        // set -a 才让 source 进来的变量被导出；rm -f 让凭据不留下文件副本。
        assertTrue("source 没配套 set -a：$command", command.contains("set -a;"))
        assertTrue("凭据文件用完没删：$command", command.contains("rm -f"))
    }

    @Test
    fun overlayParsesAsYamlWhenTheModelIsBuiltIn() {
        // 内置模型时不追加条目，但目录常量后面紧跟着 `- id: acp`，同样会被粘坏。
        val overlay = overlayOf(config(model = "deepseek-flash"))
        val models = modelsOf(overlay)

        assertEquals("models 条目数 = 内置目录：\n$overlay", DshBuiltinModelCatalog.IDS.size, models.size)
        assertEquals("内置目录被改动了：\n$overlay", DshBuiltinModelCatalog.IDS, models.map { it["id"] })
    }
}
