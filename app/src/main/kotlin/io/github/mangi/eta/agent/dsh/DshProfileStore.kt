package io.github.mangi.eta.agent.dsh

import java.io.File
import org.json.JSONObject
import org.yaml.snakeyaml.Yaml

/**
 * dsh profile 的**扩展/插件**读写（Heta 的「扩展」页面用地基）。
 *
 * 为什么是纯文件读写、不调 dsh 的 API：`dsh-plugin-manager` 自己做的就是这两件事 ——
 *   · 开关一个插件行 → 往 `cordis.patch.yml` 写一条 `disabled: true/false`；
 *   · 选/禁一个 bundle → 改 `package.json` 的 `dsh.profile.bundles`。
 * `cordis.patch.yml` 是 dsh 官方留给用户的补丁层（加载顺序：bundles → cordis.patch.yml →
 * 我们的 --patch 覆盖层），而 App **从不写它** —— 所以这里写它是安全的；反过来，**不要**去写
 * `cordis.yml`（dsh 每次启动重写它）或我们的 `heta-run-overlay.patch.yml`（每次运行重写）。
 *
 * 生效时机：profile 里 `hmr` 是 disabled，所以改动**下次对话生效**，不立即生效。
 */
internal class DshProfileStore(internal val profileDir: File) {

    private val manifestFile = File(profileDir, "package.json")
    private val patchFile = File(profileDir, "cordis.patch.yml")

    /** 读快照：manifest 里选中的 bundle + 用户补丁层里的插件行。 */
    fun read(): DshProfileSnapshot {
        val bundles = readBundles()
        val rows = readRows()
        return DshProfileSnapshot(bundles = bundles, rows = rows)
    }

    private fun readBundles(): List<String> {
        val json = runCatching { JSONObject(manifestFile.readText()) }.getOrNull() ?: return emptyList()
        val array = json.optJSONObject("dsh")
            ?.optJSONObject("profile")
            ?.optJSONArray("bundles")
            ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                array.optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    /** 用户补丁层里的行：`id` + 是否被 disabled。非 `id` 条目（insert 列表等）不在这里暴露。 */
    private fun readRows(): List<DshPluginRow> {
        val entries = loadPatch()
        return entries.mapNotNull { entry ->
            val id = (entry["id"] as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            DshPluginRow(
                id = id,
                name = entry["name"] as? String,
                enabled = entry["disabled"] != true,
            )
        }
    }

    /**
     * 开关一个插件行：在用户补丁层里 upsert 一条 `{id, disabled}`。
     *
     * 保留其余条目原样（可能有 `insert:`、`config:` 等我们不该动的东西）。
     */
    fun setPluginEnabled(id: String, enabled: Boolean): Boolean {
        if (id.isBlank()) return false
        val entries = loadPatch().toMutableList()
        val index = entries.indexOfFirst { it["id"] == id }
        if (index >= 0) {
            entries[index] = LinkedHashMap(entries[index]).apply {
                if (enabled) remove("disabled") else put("disabled", true)
            }
        } else {
            val entry = LinkedHashMap<String, Any?>().apply {
                put("id", id)
                if (!enabled) put("disabled", true)
            }
            entries += entry
        }
        return writePatch(entries)
    }

    /** 选/禁一个 bundle：改 manifest 的 `dsh.profile.bundles`（顺序保留，新增追加）。 */
    fun setBundleSelected(name: String, selected: Boolean): Boolean {
        if (name.isBlank()) return false
        val json = runCatching { JSONObject(manifestFile.readText()) }.getOrNull() ?: return false
        val profile = json.optJSONObject("dsh")?.optJSONObject("profile") ?: return false
        val current = readBundles().toMutableList()
        if (selected) {
            if (name in current) return true
            current += name
        } else {
            if (name !in current) return true
            current.remove(name)
        }
        profile.put("bundles", org.json.JSONArray(current))
        return runCatching { manifestFile.writeText(json.toString(2) + "\n") }.isSuccess
    }

    @Suppress("UNCHECKED_CAST")
    private fun loadPatch(): MutableList<MutableMap<String, Any?>> {
        if (!patchFile.isFile) return mutableListOf()
        val loaded = runCatching { Yaml().load<Any?>(patchFile.readText()) }.getOrNull()
        val list = loaded as? List<*> ?: return mutableListOf()
        return list.mapNotNull { entry ->
            (entry as? Map<String, Any?>)?.let { LinkedHashMap(it) }
        }.toMutableList()
    }

    private fun writePatch(entries: List<Map<String, Any?>>): Boolean = runCatching {
        patchFile.writeText(Yaml().dump(entries))
    }.isSuccess

    companion object {
        /** `<runtime>/root/.dsh/profiles/<profile>`（chroot 外的写法）。 */
        fun forRuntime(runtimeRoot: File, profile: String = "acp"): DshProfileStore =
            DshProfileStore(File(runtimeRoot, "root/.dsh/profiles/$profile"))
    }
}

/** 一个插件行（用户补丁层里能寻址到的条目）。 */
internal data class DshPluginRow(
    val id: String,
    val name: String?,
    val enabled: Boolean,
)

/** 读取结果：选中的 bundle + 补丁层里的行。 */
internal data class DshProfileSnapshot(
    val bundles: List<String>,
    val rows: List<DshPluginRow>,
)
