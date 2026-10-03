package io.github.mangi.eta.agent.dsh

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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

    private fun config(skillsDirectory: String = ""): DshRuntimeConfig {
        val rootfs = temporaryFolder.newFolder("dsh-runtime")
        return DshRuntimeConfig(
            rootfsPath = rootfs.absolutePath,
            providerRoute = "deepseek-official",
            model = "global:deepseek-v4.1-flash",
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
    fun overlayStillPinsTheModelAndTheRoute() {
        val overlay = overlayOf(config())

        assertTrue(overlay.contains("- id: acp\n"))
        assertTrue(overlay.contains("    provider: \"deepseek-official\"\n"))
        assertTrue(overlay.contains("    model: \"global:deepseek-v4.1-flash\"\n"))
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
}
