package io.github.mangi.eta.core

/**
 * sweep 的退避记录：残骸名单 + 尝试时间。
 *
 * **名字变了就立刻重试，否则同一批在一个窗口内只试一次** —— 这是两条回收路径共用的语义，
 * 所以编解码收在这里（`StaleRetirementSweeper` 与 `DshRuntimeInstaller` 各有一份**逐字相同**的
 * 拷贝，改语义时漏一处就是数据安全那类事故）。
 *
 * 窗口长度**故意不给默认值**：两侧策略本就不同（stamp 文件名、窗口、名单粒度各自保留），
 * 由调用方显式传入，免得有人误用另一侧的窗口。
 */
internal data class SweepStamp(val names: List<String>, val attemptedAtMs: Long)

internal fun parseSweepStamp(raw: String?): SweepStamp? {
    val lines = raw?.lines()?.filter { it.isNotBlank() } ?: return null
    val time = lines.lastOrNull()?.toLongOrNull() ?: return null
    return SweepStamp(names = lines.dropLast(1), attemptedAtMs = time)
}

/** 名单一致且在窗口内 → 跳过本次（`nowMs - attemptedAtMs` 为负即时钟回拨，按不跳过处理）。 */
internal fun shouldSkipSweep(
    stamp: SweepStamp?,
    nowMs: Long,
    names: List<String>,
    windowMs: Long,
): Boolean =
    stamp != null && stamp.names == names && nowMs - stamp.attemptedAtMs in 0 until windowMs
