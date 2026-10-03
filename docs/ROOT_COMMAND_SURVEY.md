# Root 命令执行站点调查报告

> 目的：把"这个仓库里谁在起 root 进程、各写各的超时/退出码/截断/日志"这件事一次查清，**以后不必重新研究**。
> 本文是**调查记录**，不是重构设计方案。所有结论都带 文件:行 或 提交号；查不到的地方明确写"未找到证据"。

## 0. 调查元信息

| 项 | 值 |
| --- | --- |
| 调查日期 | 2026-10-03 |
| 仓库 | `/home/samin/Heta-dsh`（WSL Ubuntu） |
| 调查开始时 HEAD | `055d01071fd3245bf8e57d7f364e047fc22515e6`（`055d010`，2026-10-03 16:59:57 +0800） |
| 调查结束时 HEAD | `6a710c1f19326438f207c8f4c0a5ee69f350e4ce`（`6a710c1`，2026-10-03 17:49:23 +0800） |
| 工作树状态 | 调查期间**只多出本文件**（`git status --porcelain` 仅 `?? docs/ROOT_COMMAND_SURVEY.md`），无生产代码改动 |
| 调查范围（代码） | `app/src/main/kotlin/**`（生产代码为准）；`app/src/test/kotlin/**` 只用于确认调用点契约，不计入站点数 |
| 调查范围（历史） | `git log --all`，共 **488** 个提交，时间跨度 **2026-03-26 → 2026-10-03**；分支：`main` + 5 个 `remotes/origin/*` |
| 行号基准 | 本文所有 `文件:行` 均**逐条在 `6a710c1` 上复核过**（66 条断言全部通过）。调查中途 HEAD 从 `055d010` 前进到 `6a710c1`；该提交只动了 `scripts/**`、`app/build.gradle.kts` 与 3 个文件的空行（含 `DshRuntimeInstaller.kt:236`），**被删的那一行在所有被引用的行号之后**，因此引用未失效 |
| 本次是否改动生产代码 | **否**。只新增本文件 |
| 仓库内其他 docs 是否被改动 | 否 |

### 复现用的检索命令（都已实际执行）

```bash
# 站点枚举
grep -rn 'ProcessBuilder'            app/src/main/kotlin --include='*.kt'
grep -rn '"su"'                      app/src/main/kotlin --include='*.kt'
grep -rn 'waitFor(|readTimeout|exitValue()|destroyForcibly' app/src/main/kotlin --include='*.kt'
grep -rn 'Runtime.getRuntime|\.exec\(|ProcessBuilder\(' app/src/main/kotlin --include='*.kt'

# 历史挖掘
git log --all --oneline
git rev-list --all --count
git log --all --oneline -i --grep='超时|死锁|卡死|卡住|挂住|吞|退出码|截断|管道|缓冲|授权|purge|清理|timeout|deadlock|hang|swallow'
git log --all --oneline -S'BoundedRootCommandExecutor'
git log --all --oneline -S'destroyForcibly'
git log --all --oneline -S'maxOutputBytes'
git log --all --oneline --follow -- <每个站点文件>
git show <sha>            # 逐个读 diff 与提交正文
```

### 我**没能查清**的部分（先说清楚）

1. **`BoundedRootCommandExecutor` 是否存在过被回滚/被降级使用的历史**：`git log --follow` 显示该文件只被 3 个提交碰过（`4a9475e` 创建、`feb618c` 改包名、`c667e6b` 适配 rootless），**没有任何一个修复类提交以它为对象**。我无法证明"曾经有人试过用它然后放弃"——**未找到证据**。所以问题一里"为什么没人用"的结论，是从**接口能力与调用点需求的比对**推出的，不是从"有人试过失败"推出的。
2. **真机日志本体**：提交正文里引用了真机现象（如"38 个挂载""291MB 残骸""3.0.6.14/15 删掉 Debian rootfs 内容"），但 logcat 原文没有进仓库，我只能引用提交正文的表述，无法独立复核设备日志。
3. **CI 是否跑过这些路径**：`488bb61` 正文明确写"`agent/dsh/**` 不在本地编译覆盖内"，`44c5d4a` 写"真正的行为测试要在能建挂载的环境里跑（CI 加一个 shell 步骤），这个还没做"。所以与 purge 有关的多数改动**当时没有被 CI 覆盖**。我确认了这些说法，但没有逐版本核对 CI 配置的演进。
4. **`su` 实现的差异**：设备上 `su` 来自 Magisk / KernelSU / 各家 ROM，行为差异（授权弹窗、`-c` 语义、`-v` 支持）只在 `DshRuntimeConfig.kt:213` 的注释与 `GoogleAppSystemizerInstaller` 的 `RootManager` 探测里被间接提到。**没有设备矩阵证据**。
5. **`AgentFileReferenceGateway` 在 UI 层的全部调用路径**：我确认了 `AgentAppState.kt:1522` 用 `scope.launch(Dispatchers.IO)` 包装，但没有穷举所有调用方，因此不能断言"绝不会在主线程调用"。
6. 本文**不评估**"删穿挂载点"在非 root 路径上的同类问题（`SafeTreeDelete` 的 13 处裸 `deleteRecursively`）——它不启动 root 进程，超出本次"root 进程站点"的范围。仅在 3.2 的事故 #2 里作为同一现场的另一半机制被引用。

---

## 1. 问题一：`BoundedRootCommandExecutor` 的接口长什么样、为什么没人用

文件：`app/src/main/kotlin/io/github/mangi/eta/agent/device/BoundedRootCommandExecutor.kt`（共 164 行）

### 1.1 接口逐项事实

| 维度 | 事实 | 证据 |
| --- | --- | --- |
| 可见性 | `internal class`，仅模块内可见 | `BoundedRootCommandExecutor.kt:17` |
| 构造参数 | `logger: AgentLogger`、`rootAvailable: () -> Boolean = { RootAccess.isGranted }` | `:17-19` |
| 生命周期 | `AutoCloseable`；`close()` 置 `closed` 并 `terminate` 所有在飞的进程 | `:20`、`:91-95` |
| **函数签名** | `fun execute(command: String, timeoutMillis: Long = 8000, maxOutputBytes: Int = 256*1024): Result` —— **非 suspend、阻塞**，签名里没有 `CoroutineScope`/`Context`/`CancellationToken` | `:24-28` |
| 实际命令 | 固定 `ProcessBuilder("su", "-c", RootCommandEnvelope(command).script)`；envelope 先 `id -u` 校验、往 **stderr** 打一个 marker、再 `exec /system/bin/sh -c <command>` | `:33`、`RootCommandEnvelope.kt:9-11` |
| 返回结构 | `Result(exitCode: Int, stdout: String, stderr: String, timedOut: Boolean, truncated: Boolean, errorCode: String)`；`ok = exitCode==0 && !timedOut && errorCode.isBlank()`；失败用 `Result.failed(code)` | `:126-146` |
| **输出类型** | **`String`，不是 `ByteArray`** | `:127-128` |
| 超时语义 | `timeoutMillis.coerceIn(500, MAX_TIMEOUT_MS)`；`MAX_TIMEOUT_MS = 30_000`；`DEFAULT_TIMEOUT_MS = 8_000`；超时时 `terminate` 且 `exitCode = -2`、`timedOut = true` | `:55`、`:71`、`:158-159` |
| 输出截断 | 上限 `DEFAULT_MAX_OUTPUT_BYTES = 256KB`，硬顶 `MAX_MAX_OUTPUT_BYTES = 2MB`；**读时即截断但仍持续排空管道**（防 64KB 管道死锁） | `:108-124`、`:160-161`、KDoc `:15` |
| 线程模型 | **每次调用**新建 `Executors.newFixedThreadPool(2)`，两个线程分别读 stdout/stderr，`finally` 里 `shutdownNow()` | `:44-53`、`:80` |
| 取消 | **只有 `close()` 这个整体取消**；没有单次执行的中断句柄 | `:91-95` |
| 异步 | 无。没有 suspend 包装、没有回调、没有 `Future` 暴露 | 整个类无 suspend 成员 |
| 日志与标签 | 固定文案：成功/失败走 `logger.debug`（`"Agent root command outcome=... exit=... timeout=... output_chars=... truncated=..."`），被拒走 `logger.warn("Agent root access outcome=denied code=ROOT_REQUIRED")`。**没有 `label`/`tag` 入参** | `:67`、`:82-87` |
| Root 探测耦合 | 内部会 `RootAccess.markDenied()`（副作用），调用方无法关闭 | `:66` |
| 权限 | 只支持 root；没有"以 user 身份跑"的参数 | `:30`、`:33` |

被引用的类型：`RootCommandEnvelope`（`RootCommandEnvelope.kt`，25 行）只做三件事——校验 uid=0、往 stderr 打 marker、`exec /system/bin/sh -c`。

### 1.2 纠正前提：它其实**有**人用，但使用是**分层**的

事实核查结果与任务描述的前提不完全一致：生产代码里有 **9 个消费者文件**引用它（含它自己共 10 个）。

**长期持有实例（1 处创建，5 处共享）**

- `agent/tool/AgentLocalTools.kt:98` 创建唯一一个长期实例 `rootCommandExecutor`，并在 `:145` 随工具集 `close()`；
  - `AgentLocalTools.kt:102` → `AgentStructuredDeviceTools(root = ...)`
  - `AgentLocalTools.kt:105` → `AgentImageTools(context, rootCommandExecutor, ...)`
  - `AgentStructuredDeviceTools.kt:38` → `AgentPersonalDataTools(root)`
  - `AgentStructuredDeviceTools.kt:39` → `AgentColorOsMemoryTools(context, root)`
  - `AgentStructuredDeviceTools.kt:41` → `AgentPrivateDatabaseTools(context, root)`

**一次性构造（3 处，每命令一个执行器）**

- `AgentFileReferenceGateway.kt:30` 与 `:49`：`BoundedRootCommandExecutor(logger).use { ... }`
- `RootAccess.kt:91`：探测 `id -u`，并包在 `runInterruptible` 里（`:90`）
- `AgentAppRoot.kt:462`：OPlus 系设备直达耗电行为控制页

**完全不用它的生产家族（这才是"没人用"的真实形状）**

| 家族 | 文件 | 为什么关键 |
| --- | --- | --- |
| terminal | `agent/terminal/ShellProcessSupervisor.kt`、`DetachedTaskSupervisor.kt`、`RootShellTerminalController.kt`、`ConsoleSessionController.kt`、`UserTerminalController.kt`、`LinuxFileExplorer.kt` | 全部经 `ShellProcessSupervisor.startShellProcess`（`:39`）一个收口 |
| 安装器 | `AlpineEnvironmentInstaller.kt:416 InstallerShellRunner`、`DebianEnvironmentInstaller.kt`、`LinuxPackageProfiles.kt`、`LinuxApkAnalysisInstaller.kt`、`PinnedLinuxToolInstaller.kt`、`RootlessLinuxInstaller.kt` | 也经 `InstallerShellRunner` → `startShellProcess`（`AlpineEnvironmentInstaller.kt:427`） |
| dsh 运行时 | `DshRuntimeConfig.kt:142`、`DshAcpClient.kt:89` | 长驻 `su -c` 子进程 + JSON-RPC |
| 设备控制 | `agent/device/RootShellDeviceController.kt:1171` | 截图/点击/dumpsys 的一次性 `su -c` |
| 系统化安装 | `systemizer/GoogleAppSystemizerInstaller.kt:151` | 模块安装 + `su -v` 探测 |

即：**device/tool 层（读数据、设备直达）用它；terminal/dsh/systemizer 层（会话、安装、运行时）不用它。** 这个分界不是随机的——见 1.3。

### 1.3 具体哪一块不合用（逐条，带调用点证据）

**（1）超时上限 30s —— 结构性挡住所有安装器。**
`MAX_TIMEOUT_MS = 30_000`（`:159`）。而实际需求是：Debian 常用工具安装 `900s`（`DebianEnvironmentInstaller.kt:293`）、Alpine `600s`（`AlpineEnvironmentInstaller.kt:312`）、包 profile 安装 `600s`（`LinuxPackageProfiles.kt:272`）、终端单次命令上限 `180s`（`RootShellTerminalController.kt:30 MAX_TIMEOUT_SECONDS`）。**没有任何安装/长任务路径能塞进 30s。**

**（2）"跑到退出才返回" —— 挡住所有常驻/守护/会话。**
`execute` 在 `:55` 阻塞等 `waitFor` 并返回；守护任务要求"启动完就返回 PID、进程继续活"（`DetachedTaskSupervisor.kt:307-323`：写 pidfile、轮询 pid、返回 `DetachedTask`），交互式会话要求进程活到用户关闭（`ShellProcessSupervisor.kt:39 startShellProcess` 返回 `Process` 本身，`ConsoleSessionController.kt:80` 用 `pty = true`）。这两类语义与"跑完才返回"根本冲突。

**（3）无 stdin、无流式输出 —— 挡住交互式终端。**
`execute` 只读 stdout/stderr（`:46-53`），没有写 `process.outputStream` 的口子。而 `ConsoleSessionController.kt:124-127` 要往进程 stdin 写 bootstrap、`:143` 要持续写按键字节；`UserTerminalController.kt:130-133` 需要把输出按增量推给 UI（`STREAM_MAX_BYTES = 1MB`）。统一入口是"攒完再给"，不是流。

**（4）返回 `String` 而非 `ByteArray` —— 挡住二进制载荷。**
`Result.stdout/stderr` 是 `String`（`:127-128`）。而 `RootShellDeviceController.kt:789` 跑 `runSuBytes("screencap -p", 8)` 拿的是 **PNG 二进制**；`LinuxFileExplorer.kt:111` 用 `head -c $maxBytes` 读文件字节。走 `String` 会在解码处损坏数据。

**（5）只支持 root 身份 —— 挡住免 root / user 身份路径。**
构造里 `rootAvailable` 为假时直接 `Result.failed("ROOT_REQUIRED")`（`:30`），且命令固定 `su -c`（`:33`）。而 terminal 家族有整套身份矩阵：`ShellProcessSupervisor.kt:51-56` 支持 `identity == "root" || "user"`，并在 `:85` 用 `sh -c`；`DetachedTaskSupervisor.kt:403` 同样；`LinuxFileExplorer.kt:83/116` 按 PRoot 后端在 `user`/`root` 之间切换。**执行器没有 `identity` 参数。**

**（6）没有取消句柄 —— 与租约/停止机制不兼容。**
只有整体 `close()`（`:91`）。而仓库里有按任务/按会话的停止语义：`ExecutionLeaseRegistry`、`DetachedTaskSupervisor.stop(id)`（`:233` 起）、`ShellProcessSupervisor.terminateProcessTree`（`:140`）、`terminalController.interruptAll()`（`AgentLocalTools.kt:144`）。执行器无法"只中止某一条在飞的命令"。

**（7）日志没有 label —— 已经因此吃过事故（见事故 #12）。**
`:82-87` 的文案是固定字符串，无法区分"这条 su 是清理还是探测"。而 `DshRuntimeInstaller.kt:210 logPurgeOutcome(…, label)` 专门加了 label 参数，`a68fa5a` 的正文还记着"同一类问题在这个会话里已经是第三次"。**统一入口在日志归属这一点上比手写站点更弱。**

**（8）每次调用新建线程池 —— 高频调用有额外开销与语义副作用。**
`:44` 每次 `newFixedThreadPool(2)`。对 `AgentFileReferenceGateway.kt:30` 那种"每命令一个执行器"的写法（`use{}` 后立即 `close()`），`activeProcesses` 注册表（`:21`）与取消能力实际被架空：一个执行器只服务一条命令。

**关于"需要 Context/协程作用域而调用点没有"——这一条不成立。**
执行器**不**需要 `Context`（构造只要 `AgentLogger` 和一个 `() -> Boolean`），也不要求调用方提供协程作用域。相反，是**调用方自己**必须保证不在主线程调用：`RootAccess.kt:90` 用 `runInterruptible`、`AgentAppState.kt:1522` 用 `scope.launch(Dispatchers.IO)`。`AgentLocalTools.execute`（`:150`）本身是阻塞式 `ToolExecutor` 接口，与阻塞执行器天然匹配。**所以"太重"的不是依赖，而是能力面。**

### 1.4 结论

不合用的不是"接口太重/需要 Context"，而是**能力面单向偏窄**：它被设计成
**"一次性、有界、只读型 root 短命令"**（≤30s、≤2MB、String、root-only、不交互、不可单点取消、无标签）。

这个窄面**恰好**匹配 device/tool 层（读受保护数据、设备直达、探测），所以那一层用了它；
而 terminal/dsh/systemizer 层需要的是**长时、常驻、交互、流式、二进制、非 root 身份**——执行器一项都不提供，
所以那一层另外长出了 `ShellProcessSupervisor` + `InstallerShellRunner`，以及 4 处完全独立的手写实现。
**没有任何证据表明有人试过用执行器然后放弃**（见 0 节第 1 条）。

---

## 2. 问题二：仓库里所有"自己起 root 进程"的站点清单与分类

分类口径按任务给定：
**【数据安全类】** = 安装器 / 清理 / purge / 读写受保护数据；
**【终端·UI 类】** = 交互式会话、守护任务、文件浏览器、截图等。

### 2.1 站点总表（生产代码 `app/src/main/kotlin`）

先给全仓的进程启动点原始证据（`ProcessBuilder` 共 15 处命中，其中 3 处是注释/文档字符串）：

```
DshRuntimeInstaller.kt:182      ProcessBuilder("su", "-c", …)      ← 真启动
DshAcpClient.kt:89              ProcessBuilder(command)            ← 真启动（command 来自 DshRuntimeConfig.kt:142 = ["su","-c",…]）
DetachedTaskSupervisor.kt:310   ProcessBuilder("sh", "-c", …)      ← 真启动（user）
DetachedTaskSupervisor.kt:401   ProcessBuilder("su", "-c", …)      ← 真启动
DetachedTaskSupervisor.kt:403   ProcessBuilder("sh", "-c", …)      ← 真启动（user）
ShellProcessSupervisor.kt:83    ProcessBuilder("su", "-c", …)      ← 真启动
ShellProcessSupervisor.kt:85    ProcessBuilder("sh", "-c", …)      ← 真启动（user）
ShellProcessSupervisor.kt:479   ProcessBuilder("su", "-c", …)      ← 真启动
ShellProcessSupervisor.kt:481   ProcessBuilder("sh", "-c", …)      ← 真启动（user）
RootShellDeviceController.kt:1171 ProcessBuilder(*command)         ← 真启动（实参 "su","-c",… 于 :1155）
BoundedRootCommandExecutor.kt:33  ProcessBuilder("su", "-c", …)    ← 统一入口自身
GoogleAppSystemizerInstaller.kt:151 ProcessBuilder(*command)       ← 真启动（实参 "su","-c"/"su","-v" 于 :146/:112）
DshRuntimeConfig.kt:37 / :213      （注释）                         ← 非启动
ShellProcessSupervisor.kt:36       （注释）                         ← 非启动
```

实际站点（按"一个独立的命令构造/进程管理实现"计数）：

| # | 站点（文件:行） | 用途一句话 | 分类 |
| --- | --- | --- | --- |
| S01 | `agent/device/BoundedRootCommandExecutor.kt:33`（↑`:24` execute） | 统一入口：一次性有界 root 短命令 | 【数据安全类】工具底座 |
| S02 | `agent/device/RootShellDeviceController.kt:1155 → :1171`（`su -c`，`runSuText`/`runSuBytes`） | 设备直达一次性 su：点击/滑动/输入/`dumpsys`/`screencap -p`/`wm size` | 【终端·UI 类】截图·UI 操作 |
| S03 | `agent/dsh/DshRuntimeInstaller.kt:182`（`purgeLeftovers`） | 以 root 摘挂载+清运行时目录/残骸（`runtimePurgeScript`） | 【数据安全类】清理·purge |
| S04 | `agent/dsh/DshRuntimeConfig.kt:142`（`command()`）+ `agent/dsh/DshAcpClient.kt:89` | 经 `su -c` + chroot 启动**长驻** dsh ACP 运行时（JSON-RPC） | 【数据安全类】运行时·受保护数据 |
| S05 | `agent/terminal/ShellProcessSupervisor.kt:83`（↑`:39 startShellProcess`） | 托管 shell 会话启动（root=su / user=sh，含 PTY、进程树、归属 token） | 【终端·UI 类】交互式会话 |
| S06 | `agent/terminal/ShellProcessSupervisor.kt:479`（`runSignalCommand`） | 以 root 发杀进程树信号（带归属校验） | 【终端·UI 类】会话回收 |
| S07 | `agent/terminal/ShellProcessSupervisor.kt:543 runOneShotShell`（↑`:581` waitFor） | 终端侧一次性命令原语（带超时+进程树回收+有界输出） | 【终端·UI 类】通用原语 |
| S08 | `agent/terminal/DetachedTaskSupervisor.kt:401`（`launchRaw`） | 裸启动器：以 root 起脱离会话的守护任务（**刻意绕开** S05 的托管壳） | 【终端·UI 类】守护任务 |
| S09 | `agent/terminal/DetachedTaskSupervisor.kt:310`（`startUserDaemon`） | 以 `sh -c` 起 user 守护任务（写 pidfile、`setsid -w`） | 【终端·UI 类】守护任务 |
| S10 | `agent/terminal/DetachedTaskSupervisor.kt:341` | 守护任务租约跟随线程：**无超时** `process.waitFor()` | 【终端·UI 类】守护任务 |
| S11 | `agent/terminal/DetachedTaskSupervisor.kt:413` | 守护任务一次性探活/清理命令 | 【终端·UI 类】守护任务 |
| S12 | `agent/terminal/RootShellTerminalController.kt:169` | 会话退出等待线程：**无超时** `process.waitFor()` | 【终端·UI 类】交互式会话 |
| S13 | `agent/terminal/RootShellTerminalController.kt:310`（`:274`/`:939` 起进程） | 按 job 超时执行会话内命令、取退出码 | 【终端·UI 类】交互式会话 |
| S14 | `agent/terminal/ConsoleSessionController.kt:112`（`:80` 起 PTY 进程） | PTY 控制台会话；退出等待**无超时** | 【终端·UI 类】交互式会话 |
| S15 | `agent/terminal/UserTerminalController.kt:137`（`:109` 起进程） | 用户常驻手动终端；退出等待**无超时**，输出 1MB 有界 | 【终端·UI 类】交互式会话 |
| S16 | `agent/terminal/LinuxFileExplorer.kt:81`、`:114` | Linux rootfs 只读文件浏览/读文件（按后端切 `root`/`user`） | 【终端·UI 类】文件浏览器 |
| S17 | `agent/terminal/AlpineEnvironmentInstaller.kt:416 InstallerShellRunner.run`（↑`:427` 用 S05 起进程 + 自写输出收集 `:443`） | 安装器统一跑命令：**自己实现了一遍 `runOneShotShell`** | 【数据安全类】安装器 |
| S18 | `systemizer/GoogleAppSystemizerInstaller.kt:146`、`:112`、`:151` | 以 `su -c` 装 Google App 系统模块；`su -v` 探测 root 管理器 | 【数据安全类】安装器 |
| S19 | `agent/dsh/DshAcpClient.kt:301`、`:305` | ACP 子进程 EOF 时的有界收尸（1s）+ 退出码 + stderr 尾部 | 【数据安全类】运行时 |
| S20 | `agent/device/RootAccess.kt:90-92` | root 可用性探测（`id -u`，30s） | 【数据安全类】权限探测（走 S01） |

**调用方（不是站点，但决定站点的需求）**：`DebianEnvironmentInstaller.kt:189/246/271`、`LinuxPackageProfiles.kt:230/252`、`LinuxApkAnalysisInstaller.kt:213/281/309/342/357`、`PinnedLinuxToolInstaller.kt:60`、`RootlessLinuxInstaller.kt:139` —— 全部经 **S17 `InstallerShellRunner`** 落进 S05。

### 2.2 由清单得到的两条结构性事实

**事实 A：terminal + 安装器这一大族**（S05–S17）**已经有一个统一收口**，只是不是 `BoundedRootCommandExecutor`：
`ShellProcessSupervisor.startShellProcess`（`:39`）是唯一的进程启动点，`runOneShotShell`（`:543`）是唯一的一次性原语。它们有：超时、进程树终止、归属 token 防 PID 复用（`ETA_PROCESS_OWNER_ENV` `:511`）、输出有界（`SessionStatusProtocol.kt:45 ByteArrayOutputCollector`）、身份矩阵（root/user/PRoot）。

**事实 B：真正"各自手写、谁也不沾"的独立实现只有 5 处**（另有 1 处是刻意的）：
S03（`DshRuntimeInstaller.kt:182`）、S18（`GoogleAppSystemizerInstaller.kt:151`）、S02（`RootShellDeviceController.kt:1171`）、S04（`DshRuntimeConfig.kt:142`+`DshAcpClient.kt:89`）、S08（`DetachedTaskSupervisor.kt:401`，注释 `:381-384` 明说"不经过 ShellProcessSupervisor 的托管壳……壳的 wait 与退出清理正是守护任务要逃离的回收语义"），以及 S17（`InstallerShellRunner`，它复用 S05 起进程却**另写了一套读取/超时/回收**，等于 `runOneShotShell` 的重复实现）。

---

## 3. 问题三【最重要】：这份重复有没有**已观测到的成本**？

### 3.1 计数口径（先定死，避免用审美判断）

- **"事故"= 有提交正文/提交 diff 支撑的、真实发生过的缺陷**，并且能被映射到具体站点。
- 我把证据强度分三档，**不混算**：
  - **观测级**：提交正文写明真机/实机现象（含"真机日志""设备上实测""本机复现"）。
  - **审查级**：提交正文说"审查指出"，并给出明确的失效路径；未声称真机现象。
  - **潜在级**：代码里存在缺陷形状，但提交正文**明确说还没暴露**。
- **不计入事故**：纯重构、改名、包名迁移、纯新增功能、死代码清理。典型反例：`c667e6b` 删掉了 `AgentAppRoot.kt` 里两处 `Runtime.getRuntime().exec(arrayOf("su","-c","id"))` + 裸 `process.waitFor()`，但**提交正文没有声称任何事故**，所以**不计入**（虽然它是"站点各写各的"历史形态的好证据）。
- **不计入事故（范围界定）**：最新提交 `6a710c1`(10-03) 的正文报告它的"检查器反向用例"上线几分钟内抓到 2 个问题（判据匹配写错、作用域闸门没写进文档）。这两个是 **`scripts/**` 检查器脚本自身**的问题，**不涉及任何 root 进程站点**，因此不在本次计数口径内 —— 在此说明，以免被读成漏计。

### 3.2 事故清单

| # | 事故 / 缺陷 | 强度 | 站点 | 提交（日期） | 证据要点 |
| --- | --- | --- | --- | --- | --- |
| 1 | **清理无超时 + 不看退出码 + 不 umount → 升级/重装后永久"未就绪"** | 观测级 | S03 | 引入 `2ccbf79`(09-28)，修复 `0913764`(10-01) | `2ccbf79` 加入 `.start().waitFor()`（裸，无超时、不看退出码、无 umount）；`0913764` 正文："`waitFor()` 没有超时（su 卡在授权弹窗上就会把调用方线程永久挂住，界面永远'未就绪'且没有任何报错）"；同正文点名真机现象"9-30 那次 `etc/resolv.conf` EACCES 被 runCatching 吞掉就是这个形状" |
| 2 | **清理脚本认不出挂载 → `rm -rf` 删穿挂载点，真机损坏 Debian rootfs 内容（已随 3.0.6.14/15 发布）** | 观测级 | S03 | `44c5d4a`(10-01)、`e6aec9c`(10-01)、`b33c2ff`(10-01)、`90fc078`(10-02) | `b33c2ff` 正文："3.0.6.14/3.0.6.15 那个**未做路径规范化**的清理脚本认不出这些挂载，`rm -rf` 穿过了它们，删掉了 `files/terminal/debian/rootfs` 的内容（Linux 工具环境的 Debian rootfs 因此损坏，需要重装一次）"；`44c5d4a`："**先损坏，再报错。**"并给出本机同构复现；`90fc078`："3.0.6.14/15 就是这么删掉 Debian rootfs 内容的" |
| 3 | **孤儿挂载被当成阻塞条件 → 清理永远失败，安装永久卡住** | 观测级 | S03 | `b33c2ff`(10-01) | 正文首句："真机日志（**38 个挂载**把安装流程永久卡在'未就绪'）暴露出两个缺陷" |
| 4 | **清理输出按 600 字符一刀切 → 真机上排查信息只剩半截** | 观测级 | S03（`logPurgeOutcome` `:210`） | `1f67d60`(10-01) | 正文："清理输出原来按 600 字符一刀切：**真机上**十几个挂载的 `UMOUNT_FAILED`/`ORPHAN` 行一多就只剩半截，排查又得猜"；现改为按行截断 `PURGE_LOG_LINES = 24`（`DshRuntimeInstaller.kt:38`） |
| 5 | **守护任务启动器缺后台化 `&` → 前台等到 10s 超时，任务其实已启动却报失败** | 观测级（有回归测试） | S09/S11 | `bc53a37`(08-30) | 正文："setsid 分支缺少后台化符号，启动器前台等待长驻命令直到 10 秒超时，守护任务实际已启动却报失败"；diff 把 `setsid sh -c … \|\| exit 1` 改成 `… &` |
| 6 | **ACP 回包被静默丢弃 → 调用方原地等到 30 分钟超时，界面完全没反应** | 观测级 | S04（`DshAcpClient`） | `1dddfe0`(09-30) | 正文：`Channel.RENDEZVOUS` 的 `trySend` 只在已有接收者时成功，"回包落在'已登记、还没人接'的窗口里……而 `pending.remove` 已经把登记项删了，没有第二次机会——调用方随后原地等到超时"；"表现就是「**Heta 卡在 job_output 上，界面完全没反应**」"；`session/prompt` 超时 30 分钟 |
| 7 | **ACP 子进程退出码与尾部 stderr 被丢弃 → 界面只剩 `protocol-eof`，谁都没法修** | 观测级 | S04 | `3bf7d48`(10-01) | 正文："子进程为什么退出——退出码、它自己最后几行 stderr——全被丢掉，界面只剩「initialize 失败：protocol-eof」，logcat 里也只有一个 EOF，谁都没法照着修"；改后 `DshAcpClient.kt:301` 有界 1s 收尸 + `:305` 取退出码 |
| 8 | **ACP 核心绕过 `su` 直接 chroot → 命令失败被吞成 RunFailed，界面什么都没有** | 观测级 | S04（`DshRuntimeConfig.kt:142`） | `47b3663`(09-27) | 正文（英文）："The ACP core ran `chroot` directly from the app process, which has no permission to chroot at all — the command failed immediately and got swallowed into a RunFailed event, **which is why the UI showed nothing**."；并说明"Eta's other root paths all go through `su -c`" |
| 9 | **重装先删就绪标记 → su 被拒或挂载 busy 时把可用状态永久弄坏** | 审查级 | S03 | `1bd8f5e`(10-01) | 正文："四条来自审查，第一条是唯一'用户主动一点就把可用状态弄坏'的路径……只要 su 被拒或 mount busy，本来能用的 dsh 就变成永久'未就绪'" |
| 10 | **sweep 丢掉 `purgeLeftovers()` 返回值仍 `deleteRecursively()` → 可能删穿技能库挂载源** | 审查级（同批另有观测） | S03（sweep 路径） | `947bd89`(10-01) | 正文："`sweepStaleRuntimes` 丢掉了 `purgeLeftovers()` 的返回值，照样 `deleteRecursively()`。Kotlin 那个是 `walkBottomUp()` 逐个 `delete()`，**会走进挂载点**——残骸里的技能库挂载源就是 App 自己的 `files/skills`……越过挂载点就把它删了" |
| 11 | **`purge` 返回 `null` 混了"su 超时"与"目录已不存在" → 日志说谎** | 审查级 | S03 | `9e3396d`(10-02) | 正文："`purge(victim)` 返回 `null` 有两种含义——su 超时，**以及目录已经不存在了**（后者其实成功）。原来 `if (exit != null)` 才复检，于是'已经清掉'也会被记成'未释放'，**日志说谎**" |
| 12 | **超时/失败日志硬编码 `"purge leftovers"` → 误导（同类问题第 3 次）** | 审查级 | S03 | `a68fa5a`(10-02) | 正文："上一轮把 `purgeLeftovers` 的日志名参数化了，但**漏了两处硬编码**……改成 `"$label …"`。（同一类问题在这个会话里已经是第三次：日志名/断言名/枚举名跟着复用而语义变了。）" |
| 13 | **让路残骸无人回收 → 真机上 291MB 磁盘增长** | 观测级 | S03 + `core/StaleRetirementSweeper` | `1f67d60`、`a4434fb`(10-02)、`96f23cc`(10-02) | `a4434fb` 正文："真机现场：`dsh-runtime.broken-1790864453989` 是**一棵完整的 291MB runtime**，下面还挂着 57 行桥接挂载"；`96f23cc`："实机观察：重启后 291M 残骸只被删到剩 1.4M" |

### 3.3 计数结论

**近 488 个提交（2026-03-26 → 2026-10-03）里，我能用提交正文/diff 证实的、与本主题相关的事故共 13 起。**

**这 13 起里，13 起（M = 13）全部落在"各站点自己手写的实现"上；其中落在统一入口 `BoundedRootCommandExecutor`（S01）上的：0 起。**

按站点分布：

| 站点 | 事故编号 | 起数 |
| --- | --- | --- |
| **S03 `DshRuntimeInstaller.kt:182`（su 清理/purge）** | #1 #2 #3 #4 #9 #10 #11 #12 #13 | **9** |
| S04 `DshRuntimeConfig.kt:142` + `DshAcpClient.kt:89`（长驻 ACP） | #6 #7 #8 | 3 |
| S09/S11 `DetachedTaskSupervisor`（守护任务启动器） | #5 | 1 |
| S01 `BoundedRootCommandExecutor`（统一入口） | —— | **0** |

**但按"缺陷性质"再切一刀，结论会不同，必须说清：**

- **属于"进程管理手写"这一类（超时 / 退出码 / 输出截断 / 管道 / 卡住）——即统一入口本来能治的那一类——共 6 起**：
  #1（无超时+不看退出码）、#4（输出截断策略）、#5（超时误报）、#7（退出码丢弃）、#8（该不该经 su）、#6（协议层等待无界，见下注）。
  → 这 6 起散落在 **3 个不同站点**（S03、S04、S09/S11），**同类缺陷在不同站点反复出现**，这是"重复"确实有成本的直接证据，而不是审美判断。
- **属于"root 脚本语义 / 清理策略"这一类——统一入口换不掉的那一类——共 7 起**：
  #2、#3、#9、#10、#11、#12、#13。
  这一类的根因是 `runtimePurgeScript` 的 shell 内容（挂载匹配、路径规范化、umount 顺序）与"删不掉就让路"的策略，**把一个一次性执行器搬过来并不能防止它们发生**。
- 注：#6 的根因是 `Channel.RENDEZVOUS` 的 `trySend` 竞态（应用层协议设计），不是进程管理；我按"手写子进程客户端"归入 S04，但**它即使换了执行器也不会消失**——S04 根本用不了执行器（见 1.3(2)(3)）。

**一句话计数**：**近 488 个提交中可证实的 13 起事故，13 起来自各站点自己手写的实现，0 起来自统一入口；其中"进程管理手写"这一类 6 起，横跨 3 个站点。**

### 3.4 潜在但**未观测**的缺陷（明确与上面 13 起分开）

| # | 潜在缺陷 | 站点 | 证据 / 状态 |
| --- | --- | --- | --- |
| L1 | **64KB 管道写满 → 子进程卡在 `write` 永不退出（死锁）** | S03 | `e6aec9c`(10-01) 正文："管道只有 64KB，子进程若在退出前写满就会卡在 write 上永不退出，于是只能看到'20s 超时'、真正原因（脚本输出太多）反而丢了。**今天 `rm -rf` 输出为空所以没暴露**，但脚本以后加一句 `ls -la`/`du -sh` 就会变成'莫名其妙的清理超时'。" → **明确未暴露**，是审查提前修掉的 |
| L2 | **无界输出收集器**：`screencap -p` 的 PNG 全量进内存 | S02 | `RootShellDeviceController.kt:1391-1413` 的私有 `ByteArrayOutputCollector.readFrom` 是 `output.write(buffer, 0, read)`，**没有任何上限**；调用点 `:789 runSuBytes("screencap -p", 8)`。**未找到事故证据** |
| L3 | **无界输出收集器**：`StringBuilder` 追加所有 stdout 行 | S18 | `GoogleAppSystemizerInstaller.kt:158-165` `StringBuilder` + `lines.forEach { appendLine(it) }`，无上限、无截断。**未找到事故证据** |
| L4 | **默认无界的收集器被两个站点以默认值调用** | S07、S08 | `SessionStatusProtocol.kt:50 fun readFrom(input, maxBytes: Int = Int.MAX_VALUE)`；`ShellProcessSupervisor.kt:570/573` 与 `DetachedTaskSupervisor.kt:411` 都用默认值调用。对比：`UserTerminalController.kt:130/133` 显式传 `STREAM_MAX_BYTES = 1MB`、`AlpineEnvironmentInstaller.kt:443` 自带上限。**未找到事故证据**（等于说：这两条路径至今没被超长输出打过） |
| L5 | **`waitFor()` 无超时（4 处）** | S10、S12、S14、S15 | `DetachedTaskSupervisor.kt:341`、`RootShellTerminalController.kt:169`、`ConsoleSessionController.kt:112`、`UserTerminalController.kt:137`。**这 4 处看起来是刻意的**：S10 是"守护任务租约跟随者，要一直等到守护进程退出"（`:340` 起在 `isDaemon = true` 线程里），S12/S14/S15 是"会话进程退出等待者"（长驻会话本来就不该有超时）。**未找到事故证据**，且**不应**计入缺陷 |
| L6 | **执行器每次调用新建线程池** | S01 | `BoundedRootCommandExecutor.kt:44`。**未找到事故证据** |
| L7 | **`AgentFileReferenceGateway.kt:30/49` 每命令一个执行器**，使 `activeProcesses`/`close()` 的取消语义被架空 | S01 使用方式 | 代码事实；**未找到事故证据** |

### 3.5 未找到证据的部分（不要用"大概/通常"填）

- **未找到证据**：任何一起事故的根因被归到 `BoundedRootCommandExecutor` 本身。该文件只有 3 个提交碰过，且都不是修复类（见 0 节第 1 条）。
- **未找到证据**：`| tail` 吞掉退出码导致守卫失效的**实际事故**。仓库里只有一次相关记录，且是**提交信息不实**的更正，不是运行时事故：`63e3c85`(10-03) 正文："上一提交说'给调用方加 `set -o pipefail`'是**不实的**……`pipefail` 是 **shell 进程级选项**，脚本内部设它管不了外层的 `bash script.sh | tail`"。这条更像"流程/承诺失真"，**不是守卫失效事故**，我**不计入** 13 起。
- **未找到证据**：purge 超时"无上限"曾在真机上真的挂死过人。`0913764` 正文给出的失效路径（"su 卡在授权弹窗上就会把调用方线程永久挂住"）本身**没有附真机日志**；同提交能追溯的真机现象是"9-30 `etc/resolv.conf` EACCES → 永久未就绪"，那个归因于**不 umount**，不是超时。我在 3.2 的 #1 里把两者都写了，但请注意它们的证据强度不同。
- **未找到证据**：输出无限增长真的爆过内存（L2/L3/L4 全是潜在）。
- **未找到证据**：`648014c fix(coloros): 修复小布记忆查询不可用`(08-10) 与执行器限流有关。它确实改了执行器消费者（`AgentColorOsMemoryTools`，新增 `maxOutputBytes = HOOK_MAX_OUTPUT_BYTES` 于 `:72`，并把 `result.truncated` 当失败于 `:150`），但**提交正文把方案说成"在小布记忆进程内建立有界只读查询桥"，没有把根因归给执行器**；我无法证明"是执行器的 2MB 上限导致查询不可用"，因此**不计入**事故，也不写成结论。
- **未找到证据**：仓库里有任何"root 进程/子进程"相关的单元测试或 CI 步骤覆盖**真机 su**（`su` 依赖设备，测试里用的是 `NoOpLogger`/注入的假执行器，如 `RootlessDeviceToolsTest.kt:49`、`AgentFileReferenceGatewayTest.kt:152`）。

---

## 4. 问题四：能力合同表

图例：✓ = 需要且站点已实现；✗ = 需要但**没有**（缺陷/风险）；– = 不需要；
`–（刻意：不加超时）` = 该站点**有意**不设超时（见 L5 / E8，不算缺陷）。

| 站点 | 超时 | 串行化 | 同时要 stdout+stderr | 要退出码 | 要取消 | 要异步 | 要日志标签 | 需要 root | 只要"跑完就行" |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| S01 统一入口 `BoundedRootCommandExecutor` | ✓ 500–30000ms | ✗（无并发上限） | ✓（`:34` 分离两流） | ✓（`:71`，超时=-2） | ✗（仅整体 `close()`） | ✗（纯阻塞） | ✗（固定文案） | ✓ 仅 root | ✓ |
| S02 `RootShellDeviceController:1155/:1171` | ✓ 5–20s 传参 | ✓（调用方串行） | ✓（`:1172` 分离） | ✓（`:1201`） | ✗ | ✗ | 部分（`logger` 有操作名） | ✓ | ✓ |
| S03 `DshRuntimeInstaller:182` purge | ✓ 20s（`:35`） | ✓（`@Synchronized`/调用方） | ✗（`:183` merge 成一路→写文件） | ✓（`:192`，用于守卫） | ✗（仅超时强杀） | ✗（阻塞，在 `applicationScope` 上） | ✓（`label`，`:210`） | ✓ | ✗（要按行截断+守卫） |
| S04 `DshRuntimeConfig:142`+`DshAcpClient:89` | ✓ 但**按请求**（`INIT_/SESSION_/PROMPT_TIMEOUT_MS`，最长 30 分钟） | ✓（writer 串行） | ✓（`:92` 分离，`errorLoop` 独立线程） | ✓（`:301/:305` 有界 1s 收尸） | ✓（`interrupt`/`failPending`） | ✓（`suspend request`） | ✓（`closeReason` 带退出码+stderr 尾） | ✓ | ✗（长驻双向流） |
| S05 `ShellProcessSupervisor:83` 会话启动 | ✗ 不设（会话活到关闭） | ✓（`synchronized(activeProcesses)` `:96`） | ✓（`mergeStderr` 可配 `:90`） | 会话内按 job 取 | ✓（`terminateProcessTree` `:140`） | ✓（返回 `Process`，多线程消费） | ✓（归属 token `:511`） | 可选 root/user | ✗ |
| S06 `ShellProcessSupervisor:479` 信号 | ✓ 1s（`:490`） | ✓ | –（输出丢 `/dev/null`） | ✗（不看） | – | ✗ | ✗ | 按 identity | ✓ |
| S07 `runOneShotShell:543` | ✓ 传参（`:581`） | ✓（经 S05） | ✓（`:567-573` 分离） | ✓（`:594`） | ✓（超时→`terminateProcessTree` `:583`） | ✗（阻塞） | ✗ | 可选 | 部分 |
| S08 `DetachedTaskSupervisor:401` launchRaw | ✓ 传参（`:413`） | ✓ | ✗（`:405` merge） | ✓（`:420`） | 超时强杀（`:415`） | ✗ | ✗ | ✓ | ✗（要"启动即返回 pid"） |
| S09 `DetachedTaskSupervisor:310` user 守护 | ✓ 3s 等 pidfile（`:321`） | ✓（`launchLock` `:296`） | ✗（`:315` merge→日志文件） | ✗（看 pid 不看码） | ✓（`stop(id)`） | ✓（返回 `DetachedTask`） | ✓（`ETA_PROCESS_OWNER` `:311`） | ✗ user | ✗ |
| S10 `DetachedTaskSupervisor:341` | **–（刻意：不加超时）** | – | ✗ merged | ✗ | – | ✓（daemon 线程） | – | 随任务 | ✓ |
| S11 `DetachedTaskSupervisor:413` | ✓ 传参 | ✓ | ✗ merged | ✓ | 超时强杀 | ✗ | ✗ | 随任务 | ✓ |
| S12 `RootShellTerminalController:169` | **–（刻意：不加超时）** | – | ✓（独立线程 `:162-167`） | ✗（不取） | – | ✓（daemon 线程） | ✓（线程名带 sessionId） | 可选 | ✓ |
| S13 `RootShellTerminalController:310` | ✓ job 超时（`MAX 180s`） | ✓（`sessions` 锁） | ✓ | ✓（`:315`） | ✓（`interruptAll`） | ✗ | ✓ | 可选 | ✗ |
| S14 `ConsoleSessionController:112` | **–（刻意：不加超时）** | ✓（`sessionLock`） | ✗ merge（PTY 本是一路） | ✗ | ✓ | ✓（流式 `onOutput`） | ✓ | 可选 | ✗ |
| S15 `UserTerminalController:137` | **–（刻意：不加超时）** | ✓ | ✓（`:130/133` 两流，1MB 有界） | ✗ | ✓ | ✓（流式） | ✓ | 可选 | ✗ |
| S16 `LinuxFileExplorer:81/:114` | ✓ 传参 | ✓ | 部分 | ✓ | 超时回收 | ✗ | ✗ | 可选 root/user | ✓（但要字节+截断判定） |
| S17 `InstallerShellRunner:416` | ✓ 15–900s | ✓ | ✗（`mergeStderr=true` `:430`） | ✓（`:459`） | ✓（`terminateProcessTree` `:454`） | ✗（`runInterruptible` `:425`） | ✗ | 可选 | ✗ |
| S18 `GoogleAppSystemizerInstaller:146/:112` | ✓ 8s（`:112/:116/:122`） | ✓ | ✗（`:152` merge） | ✓（`:175`） | 超时强杀 | ✗ | ✗ | ✓ | ✓ |
| S19 `DshAcpClient:301/:305` | ✓ 1s（`EXIT_WAIT_MS`） | – | 只要 stderr 尾 5 行 | ✓ | – | – | ✓ | – | ✓ |
| S20 `RootAccess:90-92` | ✓ 30s | ✓（`probeJob` 去重 `:59`） | 只要 stdout | ✓ | ✓（`probeJob`） | ✓（`scope.launch`） | ✓（专用日志） | 探测（`rootAvailable={true}`） | ✓ |

### 4.1 「一个统一入口能否一次性满足它们？需要几处例外？」

**不能一次性满足。** 按上表，一个入口若要覆盖全部站点，必须同时具备：

| 要求 | 提出者 | 与当前执行器冲突点 |
| --- | --- | --- |
| 超时上限 ≥ 900s（且可"无超时"） | S17、S13、S05、S10/S12/S14/S15 | `MAX_TIMEOUT_MS = 30_000`，且不允许"无超时"（`:159`、`:55`） |
| 返回 `Process` 而非"跑完的结果" | S05、S08、S09 | `execute` 语义就是等退出（`:55`） |
| stdin 可写 + 输出流式回调 | S14、S15、S05 | 无 stdin，输出攒到结束（`:46-53`） |
| `ByteArray` 输出 | S02（screencap）、S16 | `Result.stdout/stderr: String`（`:127-128`） |
| 非 root 身份（`user`/PRoot） | S05、S09、S11、S16、S17 | 固定 `su -c` + `rootAvailable` 前置失败（`:30`、`:33`） |
| 逐条取消句柄 | S04、S13、S14、S15、S09 | 只有整体 `close()`（`:91`） |
| 调用方 `label` | S03（事故 #12 的教训）、S12 | 固定文案（`:82-87`） |
| 非 `su -c` 的 su 调用形态 | S18（`su -v` 探测 `:112`） | 硬编码 `su -c`（`:33`） |

**例外清单**（每条写清为什么不能走"统一的一次性 root 短命令入口"）：

- **E1 交互式/常驻会话**（S05、S13、S14、S15，以及 `RootShellTerminalController.kt:274/:939`、`ConsoleSessionController.kt:80`、`UserTerminalController.kt:109`）
  理由：需要"进程活得比调用长" + 持续 stdin + 流式输出 + PTY。执行器是"阻塞到退出才返回"，且无 stdin 写入面。**这不是可以靠调参绕开的，是语义不同。**
- **E2 守护任务启动**（S08、S09、S11）
  理由：需要"启动完立即返回 PID，进程继续活"，并要 `setsid` 脱离会话、pidfile 握手、按 pid 探活/停止（`DetachedTaskSupervisor.kt:307-323`）。执行器必然等退出，会把守护任务变成"调一次卡到任务结束"。
- **E3 长时安装**（S17 及其 6 个调用方：`DebianEnvironmentInstaller` 900s、`AlpineEnvironmentInstaller`/`LinuxPackageProfiles` 600s、`LinuxApkAnalysisInstaller`、`PinnedLinuxToolInstaller`、`RootlessLinuxInstaller`）
  理由：单命令 600–900s，超过执行器 30s 硬顶一个数量级；且需要 `mergeStderr=true`（`:430`）与进程树回收。
- **E4 二进制输出**（S02 的 `runSuBytes("screencap -p")`、S16 的 `head -c` 读文件）
  理由：执行器返回 `String`，二进制载荷会在 UTF-8 解码时损坏；且 256KB/2MB 上限对整屏 PNG 偏紧。
- **E5 长驻双向协议子进程**（S04：`DshRuntimeConfig.kt:142` + `DshAcpClient.kt:89`）
  理由：一个进程活整场对话，靠 stdin/stdout 上的 JSON-RPC 多轮交互，超时是**按请求**而非按进程（`PROMPT_TIMEOUT_MS` 30 分钟）。执行器的模型是"一条命令一个结果"。
- **E6 非 root 身份**（S05/S09/S11/S16/S17 的 `user`/PRoot 分支）
  理由：执行器只发 `su -c` 且 `rootAvailable` 为假就直接失败（`:30`）；这些站点在免 root 设备上必须能跑（`ShellProcessSupervisor.kt:85`、`DetachedTaskSupervisor.kt:403`、`LinuxFileExplorer.kt:83/116`）。
- **E7 非 `su -c` 的 su 调用形态**（S18 的 root 管理器探测）
  理由：`GoogleAppSystemizerInstaller.kt:112` 需要 `su -v`；执行器硬编码 `su -c`（`:33`），无法表达。
- **E8 刻意的无超时等待**（S10、S12、S14、S15 的退出等待者）
  理由：这些线程的职责就是"进程活着我就等着"，加超时反而错（守护任务租约、会话退出通知）。**这是"不该统一"的例外，不是"还没统一"的例外。**

**可以迁进统一入口的（不是例外，是候选）**：

- **S03 `DshRuntimeInstaller.kt:182`** —— 最值得考虑的一个，因为它独占 9/13 起事故。它当前的需求（20s 超时 < 30s 上限、要看退出码、输出按行截断 24 行）**落在执行器的能力面之内**；执行器还会自动解决 L1（读时截断但持续排空管道）。真正的成本在于它现在"输出重定向到临时文件"是有意为之（`:177-179` 的注释），迁移要重新论证这一点。
- **S02、S18** —— 需求（5–20s、8s、要退出码、要输出）也在执行器能力面内；但 S02 的 `screencap` 走 `runSuBytes`（**E4**）需要拆开处理：文本命令可迁，二进制命令不可迁。
- **S07 与 S17 的重复** —— `InstallerShellRunner`（`AlpineEnvironmentInstaller.kt:416`）与 `runOneShotShell`（`ShellProcessSupervisor.kt:543`）是同一件事的两份实现（起进程 + 排空双流 + 超时 + 进程树回收），且两者都在"terminal 家族"内部——这是**终端家族内部的重复**，不是与执行器的重复。

---

## 5. 结论

### 选定：**B. 只对新代码立规矩**

必须三选一，我选 **B**，理由全部来自上面的计数与表格：

1. **统一入口的事故记录是 0，而手写站点是 13/13（3.3）。** 488 个提交里 13 起可证实的事故全部落在不用执行器的站点上，执行器本身没有任何一个修复类提交（0 节第 1 条）。**所以"新的一次性 root 短命令必须走统一入口"是有证据支撑的规矩。**
2. **但代价的大头不在"重复"本身，而在一个站点的脚本语义。** S03 独占 9/13 起（3.3），其中 7 起属于"root 脚本语义/清理策略"（#2 #3 #9 #10 #11 #12 #13）——**把执行器搬过去并不能防止这些**。把"迁移 20 个站点"当成主要收益，是被计数反驳的。
3. **例外是结构性的，不是欠缺的（4.1 的 E1–E8）。** 至少 5 个站点家族（交互式会话、守护任务、长时安装、长驻 ACP、非 root 身份）需要的能力——无超时/长超时、返回 `Process`、stdin、流式、`ByteArray`、`user` 身份、逐条取消——与执行器当前窄面**方向性冲突**。要让一个入口一次性满足它们，等于把它重写成 `ShellProcessSupervisor` 的同级抽象；而执行器**今天 0 事故的纪录，恰有一部分来自它足够窄**。
4. **A 不成立**：有 6 起"进程管理手写"类事故横跨 3 个站点（含 1 起随 3.0.6.14/15 发布的真机数据损坏 #2）。这不是审美问题，是有代价的。
5. **C 不成立（作为"迁移旧站点"的整体动作）**：改动面要同时动 terminal/dsh/systemizer 三个家族与 6 个安装器调用方，而最大收益点（S03）迁不迁都不影响 7/13 起事故的复发；且 `488bb61` 已记录过"`agent/dsh/**` 不在本地编译覆盖内"、`44c5d4a` 记录过"真正的行为测试还没做"——在这些路径上做大迁移的风险高于收益。

**B 的具体含义（不含重构设计，只记录边界）**：

- 新的一次性、有界、root 短命令 → 必须走 S01；**不去动**旧的 5 处独立实现。
- 若要让规矩真的立得住，至少要补上 4.1 里被点名的具体不合用点：`label` 入参（事故 #12 的教训）、`ByteArray` 支持（E4）、超时上限可配（E3）、以及"允许无超时/返回 `Process`"这一层是否需要——后者一旦要做，就等于承认需要第二个入口（`ShellProcessSupervisor` 已经是它）。
- 与 B 配套的一个**小而具体**的动作（可选，非本文要求）：把 S17 `InstallerShellRunner` 与 S07 `runOneShotShell` 的重复收掉——这是**同一个家族内部**的重复，风险面比跨家族迁移小得多。

---

## 附录 A：站点与关键常量速查

| 常量 | 值 | 位置 |
| --- | --- | --- |
| 执行器默认/最大超时 | 8_000ms / 30_000ms | `BoundedRootCommandExecutor.kt:158-159` |
| 执行器默认/最大输出 | 256KB / 2MB | `BoundedRootCommandExecutor.kt:160-161` |
| 执行器 IO join 超时 | 2_000ms | `BoundedRootCommandExecutor.kt:162` |
| purge 超时 / 日志行数 | 20_000ms / 24 行 | `DshRuntimeInstaller.kt:35`、`:38` |
| 残骸 sweep 上限 / 重试窗口 | 2 个 / 24h | `DshRuntimeInstaller.kt:41`、`:47` |
| 终端单命令超时上限 | 默认 30s / 最大 180s | `RootShellTerminalController.kt:29-30` |
| 终端读文件上限 | 256KB | `RootShellTerminalController.kt:33` |
| 输出字符上限 | 16_000 | `RootShellTerminalController.kt:32` |
| 用户终端流上限 | 1MB | `UserTerminalController.kt:32` |
| 守护任务日志读取上限 | 64KB | `DetachedTaskSupervisor.kt:72` |
| Alpine 安装/输出上限 | 600s / 64KB | `AlpineEnvironmentInstaller.kt:312`、`:417` |
| Debian 常用工具超时 | 900s | `DebianEnvironmentInstaller.kt:293` |
| 包 profile 安装超时 | 600s | `LinuxPackageProfiles.kt:272` |
| LinuxFileExplorer 默认读 | 256KB | `LinuxFileExplorer.kt:12` |
| `ByteArrayOutputCollector` 默认上限 | `Int.MAX_VALUE`（无界） | `SessionStatusProtocol.kt:50` |
| 归属标记环境变量 | `ETA_PROCESS_OWNER` | `ShellProcessSupervisor.kt:511` |

## 附录 B：本次调查产出的原始数据

调查过程中的原始输出（提交列表、关键词命中、逐提交 diff）保存在仓库之外，未纳入版本控制：
`E:\WSL\Ubuntu\survey_out\`（`00_head.txt`、`01_oneline_all.txt`、`03_keyword_grep.txt`、`04_pickaxe.txt`、`10_stats.txt`、`11_patches_core.txt`、`12_patches_purge.txt`、`13_patches_rest.txt`、`20_executor_consumers.txt`、`22_file_history.txt` 等）。
如需复核计数，用 0 节列出的命令即可重跑。
