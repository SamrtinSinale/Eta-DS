package io.github.mangi.eta.core

/**
 * 读取流时的"上限"取值。**存在的意义是让它可被 grep**。
 *
 * 背景：仓库里曾有 7 处 `readFrom(input)` 靠**默认参数** `Int.MAX_VALUE` 隐式无上限，
 * 读代码的人和 grep 都看不出"谁是无界的"。现在无上限必须显式传这个常量，而它的使用点由
 * `scripts/check-invariants.sh` 的白名单登记（每条要写理由）。
 *
 * 为什么允许无上限：这些站点是**终端会话的完整输出**，静默截断用户看得见的内容比无界更糟。
 * 所以这一版只把"隐式"改成"显式"，**不改任何行为**。
 */
internal object CollectLimit {
    const val UNBOUNDED = Int.MAX_VALUE
}
