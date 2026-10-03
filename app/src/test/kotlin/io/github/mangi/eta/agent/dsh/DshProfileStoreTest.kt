package io.github.mangi.eta.agent.dsh

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `DshProfileStore` 的纯 JVM 单测（不碰 Android 日志，所以不需要 Robolectric）。
 *
 * 这类"文件读写 + 保留其余内容"的代码最容易写错：把别人的 `insert:` 条目顺手删掉、把 `disabled`
 * 写在错的对象上、或者把 profile 清单的其它字段弄丢。所以每个用例都断言**其余内容原样**；
 * 文本层面的细节在 `DshPatchDocumentTest` 里。
 */
class DshProfileStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun profile(
        manifest: String = """
            {
              "name": "dsh-profile-acp",
              "private": true,
              "dependencies": {},
              "dsh": { "profile": { "bundles": ["@deepseek-ai/dsh-base", "@deepseek-ai/dsh-acp-app"] } }
            }
        """.trimIndent(),
        patch: String = "# 用户自己的补丁层\n[]\n",
    ): DshProfileStore {
        val dir = temporaryFolder.newFolder("profiles", "acp")
        File(dir, "package.json").writeText(manifest)
        File(dir, "cordis.patch.yml").writeText(patch)
        return DshProfileStore(dir)
    }

    @Test
    fun readsBundlesFromManifest() {
        val snapshot = profile().read()
        assertEquals(listOf("@deepseek-ai/dsh-base", "@deepseek-ai/dsh-acp-app"), snapshot.bundles)
    }

    @Test
    fun emptyPatchHasNoRows() {
        assertTrue(profile().read().rows.isEmpty())
    }

    @Test
    fun missingPatchFileIsTreatedAsNoOverride() {
        val dir = temporaryFolder.newFolder("no-patch")
        File(dir, "package.json").writeText("{}")
        val store = DshProfileStore(dir)
        assertTrue(store.read().rows.isEmpty())
        assertTrue("没有补丁文件也要能开关（官方 ENOENT 当空序列）", store.setPluginEnabled("x", null, true) is DshProfileWrite.Ok)
        assertEquals(DshRowState.ENABLED, store.read().rows.single().state)
    }

    @Test
    fun disablingWritesThroughAndKeepsOtherEntries() {
        val store = profile(
            patch = """
                - insert:
                    - id: tool-plugin-manager
                      name: '@deepseek-ai/dsh-plugin-manager/tools'
                - id: some-plugin
            """.trimIndent() + "\n",
        )
        assertTrue(store.setPluginEnabled("some-plugin", null, enabled = false) is DshProfileWrite.Ok)

        val text = File(store.profileDir, "cordis.patch.yml").readText()
        assertTrue("insert 条目必须原样保留", text.contains("tool-plugin-manager"))
        val rows = store.read().rows
        assertEquals(
            "带 id 的顶层行与 insert 里的嵌套行都要列出来，但分组条目本身不算",
            listOf("tool-plugin-manager", "some-plugin"),
            rows.map { it.patchId },
        )
        assertEquals(DshRowState.DISABLED, rows.last().state)
    }

    @Test
    fun enablingWritesFalseAndReportsNoChangeWhenAlreadyThere() {
        val store = profile(patch = "- id: some-plugin\n  disabled: true\n")
        assertEquals(DshProfileWrite.Ok(changed = true), store.setPluginEnabled("some-plugin", null, enabled = true))
        assertTrue(File(store.profileDir, "cordis.patch.yml").readText().contains("  disabled: false\n"))
        assertEquals(
            "已经是这个状态就别再动文件",
            DshProfileWrite.Ok(changed = false),
            store.setPluginEnabled("some-plugin", null, enabled = true),
        )
    }

    @Test
    fun togglingABundleProvidedRowAppendsAnOverride() {
        // bundle 自带的插件不在 profile 补丁层里：开关只能**追加**一条覆盖行（我们从不改 bundle 的文件）。
        val store = profile(patch = "# 只有注释\n[]\n")
        assertTrue(store.setPluginEnabled("session-title-llm", "@deepseek-ai/dsh-session-title", true) is DshProfileWrite.Ok)
        val text = File(store.profileDir, "cordis.patch.yml").readText()
        assertTrue("注释要还在", text.startsWith("# 只有注释\n"))
        assertTrue("覆盖行要在、且带 disabled: false", text.contains("- id: session-title-llm\n  disabled: false\n"))
    }

    @Test
    fun rejectsWhenThePatchIsNotASequenceAndLeavesItAlone() {
        val original = "key: value\n"
        val store = profile(patch = original)
        val result = store.setPluginEnabled("x", null, enabled = false)
        assertTrue(result is DshProfileWrite.Rejected)
        assertTrue("拒绝时必须给原因", (result as DshProfileWrite.Rejected).reason.isNotBlank())
        assertEquals("拒绝就一个字都不能动", original, File(store.profileDir, "cordis.patch.yml").readText())
    }

    @Test
    fun selectsAndDeselectsBundles() {
        val store = profile()
        assertEquals(
            DshProfileWrite.Ok(changed = true),
            store.setBundleSelected("@deepseek-ai/dsh-experimental-auto-review", selected = true),
        )
        assertEquals(
            listOf("@deepseek-ai/dsh-base", "@deepseek-ai/dsh-acp-app", "@deepseek-ai/dsh-experimental-auto-review"),
            store.read().bundles,
        )
        assertEquals(
            "已经是这个状态就别再动文件",
            DshProfileWrite.Ok(changed = false),
            store.setBundleSelected("@deepseek-ai/dsh-experimental-auto-review", selected = true),
        )
        assertTrue(store.setBundleSelected("@deepseek-ai/dsh-acp-app", selected = false) is DshProfileWrite.Ok)
        assertEquals(
            "去掉一个不能动到别人，顺序也要保持",
            listOf("@deepseek-ai/dsh-base", "@deepseek-ai/dsh-experimental-auto-review"),
            store.read().bundles,
        )
        assertTrue(
            store.setBundleSelected("@deepseek-ai/dsh-experimental-auto-review", selected = false)
                is DshProfileWrite.Ok,
        )
        assertEquals(listOf("@deepseek-ai/dsh-base"), store.read().bundles)
    }

    @Test
    fun bundleRewriteKeepsEveryOtherField() {
        val store = profile()
        assertTrue(store.setBundleSelected("@deepseek-ai/dsh-base", selected = false) is DshProfileWrite.Ok)
        val json = org.json.JSONObject(File(store.profileDir, "package.json").readText())
        assertEquals("dsh-profile-acp", json.getString("name"))
        assertTrue(json.getBoolean("private"))
        assertTrue("别的 dependencies 字段不能被写没", json.has("dependencies"))
        assertEquals(listOf("@deepseek-ai/dsh-acp-app"), store.read().bundles)
    }

    @Test
    fun bundleSelectionIsCreatedWhenTheProfileBlockIsMissing() {
        val store = profile(manifest = """{ "name": "x" }""")
        assertTrue(store.setBundleSelected("@deepseek-ai/dsh-base", selected = true) is DshProfileWrite.Ok)
        assertEquals(listOf("@deepseek-ai/dsh-base"), store.read().bundles)
    }

    @Test
    fun rejectsWhenThereIsNoManifest() {
        val dir = temporaryFolder.newFolder("no-manifest")
        val store = DshProfileStore(dir)
        val result = store.setBundleSelected("@deepseek-ai/dsh-base", selected = true)
        assertTrue("读不到清单就不能假装写成功", result is DshProfileWrite.Rejected)
        assertTrue(store.read().bundles.isEmpty())
    }

    @Test
    fun rejectsWhenTheManifestIsNotJson() {
        val store = profile(manifest = "这不是 JSON")
        assertTrue(store.setBundleSelected("@deepseek-ai/dsh-base", selected = true) is DshProfileWrite.Rejected)
    }

    @Test
    fun writtenFilesAreOwnerOnly() {
        // 官方的 writeFileAtomic 落的是 mode 0o600，我们按同样口径落（见 DshProfileStore.ownerOnly）。
        // 注意：`File.createTempFile` 自己**不保证** 0600（JDK 17 实测是跟着 umask 走的 0644），
        // 所以这一步是显式做的，这条断言钉的就是它。
        assumeTrue("只在 POSIX 文件系统上断言权限", File.separatorChar == '/')
        val store = profile()
        assertTrue(store.setPluginEnabled("x", null, enabled = false) is DshProfileWrite.Ok)
        assertTrue(store.setBundleSelected("@deepseek-ai/dsh-base", selected = false) is DshProfileWrite.Ok)
        val expected = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        assertEquals(expected, Files.getPosixFilePermissions(File(store.profileDir, "cordis.patch.yml").toPath()))
        assertEquals(expected, Files.getPosixFilePermissions(File(store.profileDir, "package.json").toPath()))
    }

    @Test
    fun writeLeavesNoTemporaryFileBehind() {
        val store = profile()
        assertTrue(store.setPluginEnabled("x", null, enabled = false) is DshProfileWrite.Ok)
        assertFalse(
            "临时文件必须被清掉（不然 profile 目录里会堆垃圾）",
            store.profileDir.listFiles().orEmpty().any { it.name.endsWith(".tmp") },
        )
    }
}
