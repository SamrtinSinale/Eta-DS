package io.github.mangi.eta.core

import java.io.File

/**
 * 递归删之前先确认这棵树里**没有挂载点**。
 *
 * `File.deleteRecursively()` 是 `walkBottomUp()` 逐个 `delete()` —— 它会走进挂载点，把**挂载源**
 * 的内容一起删掉。这不是理论问题：3.0.6.14/15 的清理脚本就这么穿透 bind，删掉了 Debian rootfs
 * 的内容，还留下 38 个目标已消失的孤儿挂载。
 *
 * 廉价版护栏：读一次 `/proc/self/mountinfo`（App 与那些挂载同命名空间），发现目录之下（含自身）
 * 有挂载点就**改名让路** —— rename 不穿挂载点，挂载会跟着改名走。**让路不等于释放**：目录还在
 * （挂载不解，`rm` 也删不掉），得等挂载消失后由 [StaleRetirementSweeper] 回收，或者人手动删。
 * 所以拿不到 mountinfo / 解析不了时按"有挂载"处理 —— 宁可留残骸，不可删错。
 *
 * 需要 umount + rm 的场景（随包运行时目录）走 `DshRuntimeInstaller` 那套 su 脚本，不用这个。
 */
internal object SafeTreeDelete {

    private const val MOUNT_INFO = "/proc/self/mountinfo"

    /** 结果。**让路不等于腾出空间**，调用方要区分就靠这个。 */
    internal enum class Outcome {
        /** 递归删掉了，空间真的回来了。 */
        DELETED,

        /** 树里有挂载点，改名让路：原路径腾空了，但磁盘没释放（等回收）。 */
        RETIRED,

        /** 回收路径专用：树里有挂载点，原样留着不动（也不改名）。 */
        SKIPPED,

        /**
         * 没删干净，目录还在。
         *
         * `deleteRecursively()` 返回 false 的两种情况都落这里：**删了一部分**，以及**一点都没删掉**
         * （例如整棵树都没权限）。判据是"没删干净"，**不是**"至少释放了一部分" —— 别按后者写逻辑。
         *
         * 实机就是这么收场的：290M 的 `bin/lib/opt` 删得掉，而残骸里 root 属主的那部分 App 身份
         * 删不掉，剩下 1.4M 一直留着；这种要靠提权兜底或重启后再来。
         */
        INCOMPLETE,

        /** 删不掉也搬不动。 */
        FAILED,
    }

    /**
     * 删掉 [directory]；树里有挂载点时改名让路（安装路径用）。
     *
     * @return true 表示**原路径**已经腾空（删掉或让路）—— 不代表磁盘释放了。
     */
    internal fun deleteOrRetire(directory: File): Boolean = clear(directory).cleared

    /** 同 [deleteOrRetire]，但把结果分档返回。 */
    internal fun clear(directory: File): Outcome {
        if (!directory.exists()) return Outcome.DELETED
        if (containsMountPoint(directory)) {
            val aside = File(directory.parentFile, "${directory.name}.broken-${System.currentTimeMillis()}")
            if (!directory.renameTo(aside)) return Outcome.FAILED
            AndroidAgentLogger.warn(
                "目录里有挂载点，改名让路（不跨挂载点递归删；挂载未解前空间不释放）：${aside.absolutePath}"
            )
            return Outcome.RETIRED
        }
        return if (directory.deleteRecursively()) Outcome.DELETED else Outcome.INCOMPLETE
    }

    /**
     * 回收路径用（[StaleRetirementSweeper]）：有挂载就**原样留着**，不再改名。
     *
     * 与 [clear] 的区别很要紧：残骸本来就带挂载，再 `clear` 一次只会又改一次名，
     * 每开一次 App 就多一个 `.broken-*`。
     */
    internal fun deleteIfUnmounted(directory: File): Outcome {
        if (!directory.exists()) return Outcome.DELETED
        if (containsMountPoint(directory)) return Outcome.SKIPPED
        return if (directory.deleteRecursively()) Outcome.DELETED else Outcome.INCOMPLETE
    }

    private fun containsMountPoint(directory: File): Boolean {
        // canonicalPath 只解析已存在的部分（等价 realpath -m），挂载点路径不存在也能得到结果。
        val canonical = runCatching { directory.canonicalPath }.getOrNull() ?: return true
        val lines = runCatching { File(MOUNT_INFO).readLines() }.getOrNull() ?: return true
        // 有任何一行解析不了就按"有挂载"处理（fail-closed）：宁可留残骸，不可删错。
        val raw = parseMountPoints(lines) ?: return true
        val mountPoints = raw.map { runCatching { File(it).canonicalPath }.getOrNull() ?: it }
        return hasMountUnder(mountPoints, canonical)
    }

    /**
     * 从 mountinfo 每行取出第 5 列（挂载点）并**解码转义**。
     *
     * procfs 会把空格/制表/换行/反斜杠写成 `\040`/`\011`/`\012`/`\134`。不decoding 就直接拿去
     * `File(...).canonicalPath`，解析不了 → 回落到那个**带转义的原串** → 与规范化后的路径永远
     * 对不上 → 判成"没挂载" → **fail-open**，正是要避免的方向。所以这里解码；遇到无法识别的
     * 转义就返回 null，交给调用方按 fail-closed 处理。
     *
     * @return 挂载点原串列表；任何一行有问题就返回 null。
     */
    internal fun parseMountPoints(mountInfoLines: List<String>): List<String>? {
        val result = ArrayList<String>(mountInfoLines.size)
        for (line in mountInfoLines) {
            if (line.isBlank()) continue
            val fields = line.split(' ')
            val raw = fields.getOrNull(4)?.takeIf { it.isNotBlank() } ?: return null
            result += unescapeMountPath(raw) ?: return null
        }
        return result
    }

    /** procfs 转义解码：`\040` 空格、`\011` 制表、`\012` 换行、`\134` 反斜杠；未知转义返回 null。 */
    internal fun unescapeMountPath(raw: String): String? {
        if ('\\' !in raw) return raw
        val builder = StringBuilder(raw.length)
        var index = 0
        while (index < raw.length) {
            val char = raw[index]
            if (char != '\\') {
                builder.append(char)
                index++
                continue
            }
            val code = raw.substring(index + 1, minOf(index + 4, raw.length))
            val decoded = when (code) {
                "040" -> ' '
                "011" -> '\t'
                "012" -> '\n'
                "134" -> '\\'
                else -> return null
            }
            builder.append(decoded)
            index += 4
        }
        return builder.toString()
    }

    /**
     * 纯判定（便于单测）：挂载点里有没有等于 [directory] 或落在它之下的。
     *
     * 必须是**路径边界**匹配：`/x/dsh-runtime` 不该因为 `/x/dsh-runtime.installing` 而被判成有挂载。
     */
    internal fun hasMountUnder(canonicalMountPoints: List<String>, directory: String): Boolean =
        canonicalMountPoints.any { it == directory || it.startsWith("$directory/") }

    private val Outcome.cleared: Boolean
        get() = this == Outcome.DELETED || this == Outcome.RETIRED
}
