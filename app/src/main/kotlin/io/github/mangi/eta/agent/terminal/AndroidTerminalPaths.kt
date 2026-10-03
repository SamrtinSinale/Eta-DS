package io.github.mangi.eta.agent.terminal

/**
 * Android 侧终端路径的**唯一**声明处。
 *
 * 为什么单独一个文件、而不是挂在某个 controller 上：原来同一个目录有 4 处独立声明
 * （`RootShellTerminalController` / `UserTerminalController` / `ConsoleSessionController` 各一份
 * `DEFAULT_CWD`，`TerminalRuntime` 里还有个**裸字面量**），改一次要动 9 个文件 —— 这就是
 * "某个 controller 拥有别人都得 import 的常量"造成的。放在这里，谁都能用，谁都不拥有。
 *
 * 不在此列的：`/data/local/tmp/eta_window.xml`（`RootShellDeviceController` 里 uiautomator 的
 * dump 文件）—— 它只是**共享前缀**，不是这个目录下的东西，别顺手一起改。
 */
internal object AndroidTerminalPaths {
    /** root 身份下 Android 侧的默认工作目录。 */
    const val BASE = "/data/local/tmp/eta"

    /** Android 侧共享文件夹的挂载根（`SharedFolderMounts` 用）。 */
    const val MOUNTS = "$BASE/mounts"

    /** Android 侧后台任务的日志/pid 目录（`DetachedTaskSupervisor` 用）。 */
    const val DAEMON = "$BASE/daemon"
}
