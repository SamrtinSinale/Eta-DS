package io.github.mangi.eta.agent.dsh

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * dsh profile 的**扩展/插件**读写（Heta「扩展」页面的地基）。
 *
 * 为什么是纯文件读写、不调 dsh 的 API：`dsh-plugin-manager` 自己做的就是这两件事 ——
 *   · 开关一行插件 → 往 `cordis.patch.yml` 写该行的 `disabled`（其 `writePluginEnabled`）；
 *   · 选/禁一个 bundle → 改 `package.json` 的 `dsh.profile.bundles`（其 `selectBundle` + `saveManifest`，
 *     官方就是 `JSON.stringify(manifest, undefined, 2) + "\n"`，与这里逐字一致）。
 * 所以第一期不需要 dsh 的任何 API，也不需要联网。
 *
 * 为什么写 `cordis.patch.yml` 是安全的：它是 **dsh 官方留给用户的补丁层**（加载顺序
 * bundles → `profiles/<profile>/cordis.patch.yml` → 我们的 `--patch` 覆盖层，后者最后生效），
 * 而 App 从不写它 —— 反过来，**绝不**写 `cordis.yml`（dsh 每次启动重写）或我们的
 * `heta-run-overlay.patch.yml`（每次运行重写）；也别写 `sessions/`（App 会按 mtime 清理）。
 *
 * 生效时机：profile 里 `hmr` 是 disabled，所以改动**下次对话生效**，界面必须写明。
 *
 * 本类只覆盖 profile 这一层。界面上要显示的**插件清单**还需要把各 bundle 的补丁层合并进来
 * （官方 `listPlugins` 是 `flatten(composeEntries(readProfilePatches(...)))`，并按
 * `unaddressable` / `management-required` 把改不了的行灰掉）—— 那是下一步，不是这一层的事。
 */
internal class DshProfileStore(internal val profileDir: File) {

    private val manifestFile = File(profileDir, "package.json")
    private val patchFile = File(profileDir, "cordis.patch.yml")

    /** 读快照：清单里选中的 bundle + 补丁层里能寻址的行。 */
    fun read(): DshProfileSnapshot = DshProfileSnapshot(bundles = readBundles(), rows = readRows())

    /** 清单里选中的 bundle（`dsh.profile.bundles`），顺序即加载顺序。 */
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

    private fun readRows(): List<DshPatchRow> =
        DshPatchDocument.parse(readPatchText()).rows

    /**
     * 开关一行插件。
     *
     * [patchId] / [moduleName] 与官方 `setPluginEnabled` 内部的 `row.patchId` / `row.moduleName` 同义。
     * 该行不在 profile 补丁层里时会**追加**一条覆盖行（bundle 自己的补丁层我们从不改）。
     */
    fun setPluginEnabled(patchId: String, moduleName: String?, enabled: Boolean): DshProfileWrite {
        val document = DshPatchDocument.parse(readPatchText())
        return when (val edit = document.withDisabled(patchId, moduleName, disabled = !enabled)) {
            DshPatchEdit.Unchanged -> DshProfileWrite.Ok(changed = false)
            is DshPatchEdit.Rejected -> DshProfileWrite.Rejected(edit.reason)
            is DshPatchEdit.Changed ->
                if (writePatch(edit.text)) DshProfileWrite.Ok(changed = true)
                else DshProfileWrite.Rejected("写不进去：${patchFile.absolutePath}")
        }
    }

    /**
     * 选/禁一个 bundle（官方 `selectBundle` 的落盘部分）。
     *
     * 官方在写之前还会核对"这个包到底是不是 bundle、版本兼不兼容、是不是管理插件"—— 那些要跑 dsh
     * 才知道，这里不做；界面只能列出确实存在的 bundle（否则 dsh 启动时会解析不到而失败）。
     */
    fun setBundleSelected(name: String, selected: Boolean): DshProfileWrite {
        if (name.isBlank()) return DshProfileWrite.Rejected("bundle 名是空的")
        val text = runCatching { manifestFile.readText() }.getOrNull()
            ?: return DshProfileWrite.Rejected("读不到 profile 清单：${manifestFile.absolutePath}")
        val json = runCatching { JSONObject(text) }.getOrNull()
            ?: return DshProfileWrite.Rejected("profile 清单不是 JSON：${manifestFile.absolutePath}")

        val dsh = when (val existing = json.opt("dsh")) {
            null -> JSONObject().also { json.put("dsh", it) }
            is JSONObject -> existing
            else -> return DshProfileWrite.Rejected("清单里的 dsh 不是对象，不敢改")
        }
        val profile = when (val existing = dsh.opt("profile")) {
            null -> JSONObject().also { dsh.put("profile", it) }
            is JSONObject -> existing
            else -> return DshProfileWrite.Rejected("清单里的 dsh.profile 不是对象，不敢改")
        }

        val previous = readBundles()
        val next = if (selected) {
            if (name in previous) previous else previous + name
        } else {
            previous.filter { it != name }
        }
        if (next == previous) return DshProfileWrite.Ok(changed = false)

        profile.put("bundles", JSONArray(next))
        // 与官方 saveManifest 的字节形态一致：两空格缩进 + 末尾换行。
        val rendered = json.toString(2) + "\n"
        return if (writeAtomically(manifestFile, rendered)) DshProfileWrite.Ok(changed = true)
        else DshProfileWrite.Rejected("写不进去：${manifestFile.absolutePath}")
    }

    /** 补丁层文本；文件不在就按官方的 `"[]\n"` 处理（等价于"没有覆盖"）。 */
    private fun readPatchText(): String =
        runCatching { patchFile.readText() }.getOrNull() ?: DshPatchDocument.EMPTY

    private fun writePatch(text: String): Boolean = writeAtomically(patchFile, text)

    /**
     * 原子替换：先在**同一个目录**里写临时文件，再 rename 覆盖。
     *
     * 为什么不用 `writeText` 直接覆盖：dsh 每次启动都要读这两个文件，写到一半被它读到就是
     * "initialize 失败 / protocol-eof" 那一类起不来。同目录 rename 是原子的，读到的要么是旧内容、
     * 要么是新内容，没有中间态。官方落的也是 `writeFileAtomic`（连同它那个 `mode: 0o600`，
     * 见 [ownerOnly]）。
     */
    private fun writeAtomically(target: File, text: String): Boolean = runCatching {
        profileDir.mkdirs()
        val temp = File.createTempFile(".${target.name}.", ".tmp", profileDir)
        try {
            temp.writeText(text)
            ownerOnly(temp)
            if (!temp.renameTo(target)) {
                // 有些平台不允许 rename 覆盖已存在的文件：退一步先删再 rename，仍不行就直接写。
                target.delete()
                if (!temp.renameTo(target)) target.writeText(text)
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }.isSuccess

    /**
     * 落成"只有属主可读写"（0600），与官方的 `writeFileAtomic(..., mode 0o600)` 对齐。
     *
     * 用 java.io 这个老写法而不是 `android.system.Os.chmod`：`Os` 是 android.* API，用了它这个类就
     * 没法在纯 JVM 单测里跑了（而这正是它现在能被本地 10 秒回路覆盖的原因）。在 POSIX 上它等价于
     * `chmod 600`；在别的平台上退化成功效不明的"尽力而为" —— 所以是 best effort，失败不影响落盘。
     *
     * 值不值得写这段：补丁层里可能放插件配置（某个插件要 API key 就可能落在这里），而 `root/.dsh`
     * 这个目录在真机上是 App 建的，权限位不保证是 0700 —— 官方有意写 0600，我们跟着写。
     */
    private fun ownerOnly(file: File) {
        runCatching {
            file.setReadable(false, false)
            file.setWritable(false, false)
            file.setReadable(true, true)
            file.setWritable(true, true)
        }
    }

    companion object {
        /** `<runtime>/root/.dsh/profiles/<profile>`（chroot 外的写法）。 */
        fun forRuntime(runtimeRoot: File, profile: String = "acp"): DshProfileStore =
            DshProfileStore(File(runtimeRoot, "root/.dsh/profiles/$profile"))
    }
}

/** 一次写入的结局。 */
internal sealed interface DshProfileWrite {

    /** 写成功；[changed] = false 表示本来就是想要的状态（**没动文件**）。 */
    data class Ok(val changed: Boolean) : DshProfileWrite

    /** 拒绝写（形状不认识 / 读不到 / 写不进去）；[reason] 直接给界面显示。 */
    data class Rejected(val reason: String) : DshProfileWrite
}

/** 读取结果。 */
internal data class DshProfileSnapshot(
    /** 清单里选中的 bundle（`dsh.profile.bundles`）。 */
    val bundles: List<String>,
    /** 补丁层里的行 —— **只含 profile 这一层**，不是完整插件清单（见 `DshProfileStore` 的 KDoc）。 */
    val rows: List<DshPatchRow>,
)
