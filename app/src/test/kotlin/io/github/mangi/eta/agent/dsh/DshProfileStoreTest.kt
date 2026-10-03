package io.github.mangi.eta.agent.dsh

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `DshProfileStore` 的纯 JVM 单测（不碰 Android 日志，所以不需要 Robolectric）。
 *
 * 这类"文件读写 + 保留其余内容"的代码最容易写错：把别人的 `insert:` 条目顺手删掉、或把
 * `disabled` 写在错的对象上。所以每个用例都断言**其余内容原样**。
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
        patch: String = "# user patch layer\n[]\n",
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
    fun readsRowsAndTheirEnabledState() {
        val store = profile(
            patch = """
                - id: some-plugin
                  disabled: true
                - id: another-plugin
            """.trimIndent(),
        )
        val rows = store.read().rows
        assertEquals(2, rows.size)
        assertEquals("some-plugin", rows[0].id)
        assertFalse("disabled: true 应读成未启用", rows[0].enabled)
        assertTrue("没有 disabled 就是启用", rows[1].enabled)
    }

    @Test
    fun disablingAnExistingRowKeepsOtherEntries() {
        val store = profile(
            patch = """
                - insert:
                    - id: tool-plugin-manager
                      name: '@deepseek-ai/dsh-plugin-manager/tools'
                - id: some-plugin
            """.trimIndent(),
        )
        assertTrue(store.setPluginEnabled("some-plugin", enabled = false))

        val text = File(store.profileDir, "cordis.patch.yml").readText()
        assertTrue("insert 条目必须原样保留", text.contains("tool-plugin-manager"))
        val rows = store.read().rows
        assertEquals("只应识别带 id 的行", 1, rows.size)
        assertFalse(rows[0].enabled)
    }

    @Test
    fun enablingRemovesTheDisabledKey() {
        val store = profile(
            patch = """
                - id: some-plugin
                  disabled: true
            """.trimIndent(),
        )
        assertTrue(store.setPluginEnabled("some-plugin", enabled = true))
        assertTrue(store.read().rows.single().enabled)
    }

    @Test
    fun addsARowWhenMissing() {
        val store = profile()
        assertTrue(store.setPluginEnabled("brand-new", enabled = false))
        val row = store.read().rows.single()
        assertEquals("brand-new", row.id)
        assertFalse(row.enabled)
    }

    @Test
    fun selectsAndDeselectsBundles() {
        val store = profile()
        assertTrue(store.setBundleSelected("@deepseek-ai/dsh-experimental-auto-review", selected = true))
        assertEquals(
            listOf("@deepseek-ai/dsh-base", "@deepseek-ai/dsh-acp-app", "@deepseek-ai/dsh-experimental-auto-review"),
            store.read().bundles,
        )
        assertTrue(store.setBundleSelected("@deepseek-ai/dsh-acp-app", selected = false))
        assertEquals(listOf("@deepseek-ai/dsh-base"), store.read().bundles)
    }

    @Test
    fun missingFilesAreTolerated() {
        val dir = temporaryFolder.newFolder("empty-profile")
        val store = DshProfileStore(dir)
        assertTrue(store.read().bundles.isEmpty())
        assertTrue(store.read().rows.isEmpty())
        assertFalse("manifest 不在时不该假装写成功", store.setBundleSelected("x", selected = true))
    }
}
