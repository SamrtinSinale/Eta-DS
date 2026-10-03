package io.github.mangi.eta.agent.dsh

import io.github.mangi.eta.agent.model.AgentIdentity
import android.content.Context
import android.util.Log
import io.github.mangi.eta.agent.mcp.AgentToolServerHost
import io.github.mangi.eta.agent.skill.SkillRuntime
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * DeepSeek Harness 的运行参数。
 *
 * 运行时来自随 APK 分发的内置包（见 [DshRuntimeInstaller]），不依赖用户自己安装
 * 任何 Linux 发行版或 npm 包。
 *
 * 三个必须注意的点：
 * 1. chroot 需要 root，App 进程本身没有权限，因此命令必须经 `su -c` 包装；
 * 2. 凭据通过 `export` 传进这条 shell 命令，而不是命令行参数，避免出现在进程列表里；
 * 3. ACP profile 把模型写死成内置默认值，自定义网关的模型名要靠 `--patch` 覆盖层注入。
 */
internal data class DshRuntimeConfig(
    val rootfsPath: String,
    val providerRoute: String,
    val model: String,
    val apiKey: String,
    val baseUrl: String,
    val workingDirectory: String = DshRuntimeInstaller.WORKSPACE_IN_ROOT,
    /** App 的技能库目录；会 bind 进 chroot，供 dsh 读取和写入技能。 */
    val skillsDirectory: String = "",
) {
    /**
     * ACP 进程自身的宿主工作目录。
     *
     * [workingDirectory] 是 ACP 会话内部的 cwd（"\/workspace"，只在 chroot 里存在）；
     * ProcessBuilder 用的是宿主路径，指向不存在的目录会直接 ENOENT。
     */
    val processDirectory: String = File(rootfsPath, "workspace").absolutePath

    /**
     * 传给子进程的环境。
     *
     * App 进程的环境会整份继承下去，所以任何宿主路径都会漏进 chroot。`TMPDIR` 是踩过的
     * 那个：Android 侧它是 `/data/user/0/<包名>/cache`，在 chroot 里不存在，于是 dsh 的
     * spill 目录（`mkdtempSync(join(tmpdir(), "dsh-spill-"))`）建不出来，只留一句
     * "did not activate" 的警告，大段工具输出就没地方溢写了。这里钉成 chroot 内的 /tmp。
     */
    fun environment(): Map<String, String> = buildMap {
        put("HOME", DshRuntimeInstaller.HOME_IN_ROOT)
        put("PATH", PATH_IN_ROOT)
        put("LANG", "C.UTF-8")
        put("TMPDIR", TMP_IN_ROOT)
    }

    /**
     * 把模型选择写成 profile 覆盖层，返回它在 chroot 内的路径。
     *
     * ACP profile 的默认模型取自内置目录，而自定义网关通常只放行自己的模型名
     * （例如 `cn:deepseek-v4.1-flash`），不覆盖就会在服务端被判白名单拒绝。
     *
     * 这里还要顺带修一件事：dsh 的**图片能力是按模型目录里的 `inputModalities` 判定的**。
     * 自定义网关的模型名不在目录里，dsh 就按未知 id 回落成纯文本，于是 `initialize`
     * 报 `promptCapabilities.image=false`，任何图片都会被
     * `inline image prompts were not advertised by this connection` 顶回来。
     * 模型本身能看图（同一个模型走 Eta 原生路径时图片是通的），缺的只是这句声明。
     */
    private fun writeProfileOverlay(): String? {
        if (model.isBlank()) return null
        return runCatching {
            val overlay = File(rootfsPath, OVERLAY_RELATIVE)
            overlay.parentFile?.mkdirs()
            overlay.writeText(
                buildString {
                    append(modelCatalogOverlay())
                    append("- id: acp\n")
                    append("  config:\n")
                    append("    provider: ")
                        .append(JSONObject.quote(providerRoute.ifBlank { DEFAULT_ROUTE }))
                        .append('\n')
                    append("    model: ").append(JSONObject.quote(model)).append('\n')
                    append("- id: system-prompt\n")
                    append("  config:\n")
                    // `--patch` 的 config 是**整块替换**而不是按键合并：这里少写哪个键，
                    // 就等于把 dsh 自带的那个键一并抹掉。所以 personaPrefix 必须写回来。
                    append("    personaPrefix: ").append(JSONObject.quote(PERSONA_PREFIX)).append('\n')
                    append("    personaSuffix: |\n")
                    personaSuffixLines().forEach { line -> append("      ").append(line).append('\n') }
                }
            )
            OVERLAY_IN_ROOT
        }.getOrElse { throwable ->
            Log.w(TAG, "profile overlay write failed", throwable)
            null
        }
    }

    /**
     * 生成 `llm-deepseek` 的模型目录覆盖。
     *
     * `config.models` 是**整表替换**而不是追加，所以随包 dsh 的默认目录必须完整带上，
     * 否则用户在 dsh 侧就只剩一个模型可选。目录由 `scripts/build-dsh-runtime.py`
     * 从实际随包运行时生成，升级 dsh 时 `--check` 会阻止它再次漂移。
     *
     * 目录常量是 `trimIndent()` 的产物，**结尾没有换行**：直接 `append` 会把后面那一段
     * 粘到 `contextWindow: 1000000` 同一行上——
     *
     *     contextWindow: 1000000      - id: "global:deepseek-v4.1-flash"
     *
     * dsh 用 js-yaml 解析这份覆盖层，于是它在 `initialize` 回包之前就抛
     * `YAMLException: bad indentation of a mapping entry` 退出，Heta 侧只看到
     * `protocol-eof`。所以先 `trimEnd()` 再补一个换行：常量有没有尾换行都成立。
     */
    private fun modelCatalogOverlay(): String = buildString {
        append("- id: llm-deepseek\n")
        append("  config:\n")
        append("    models:\n")
        append(DshBuiltinModelCatalog.YAML.trimEnd()).append('\n')
        if (DshBuiltinModelCatalog.IDS.none { it == model }) {
            append("      - id: ").append(JSONObject.quote(model)).append('\n')
            append("        contextWindow: ").append(DEFAULT_CONTEXT_WINDOW).append('\n')
            append("        inputModalities: [text, image]\n")
            // 省略等于"不声明"：dsh 侧只有拿到这两个键才会对已存在的会话做 system prompt
            // 与工具定义的增量更新（dsh-llm-deepseek：systemPromptUpdate === "in-history"、
            // toolUpdate ∈ {in-history, addition-only}，两者都只在"有值"时才透传）。
            // 内置目录里的 flash 两项都带，自定义模型没有理由是纯文本语义，照抄。
            append("        systemPromptUpdate: in-history\n")
            append("        toolUpdate: addition-only\n")
        }
    }

    /**
     * su 是 Eta 既有提权路径。
     *
     * 凭据**不写进这条脚本**：`su -c <script>` 的整段脚本就是 argv，`ps` 里直接可读
     * （任何当时跑着的 root 进程都能看见，包括 dsh 自己的终端）。所以凭据落在 rootfs 内的
     * 0600 文件里，脚本只 `source` 它——argv 里只剩一个文件路径。
     *
     * 注意 source 用的是**宿主路径**：这一步在 `exec chroot` 之前由宿主的 sh 执行，
     * 那时候 `/opt/dsh/...` 还不存在（它只在 chroot 里成立）。
     */
    fun command(): List<String> = listOf(SU, "-c", rootScript())

    private fun rootScript(): String {
        val overlay = writeProfileOverlay()
        val credentials = writeCredentialEnv()
        return buildString {
            append("export HOME=").append(DshRuntimeInstaller.HOME_IN_ROOT)
            append(" PATH=").append(PATH_IN_ROOT)
            append(" LANG=C.UTF-8")
            // dsh 的审批策略由 DSH_PERMISSION_MODE 决定：danger-full-access => policy=never，
            // 即不再向客户端要审批（Eta 侧没有审批 UI，也不打算有）。
            append(" DSH_PERMISSION_MODE=").append(PERMISSION_MODE)
            append("; ")
            if (credentials != null) {
                // set -a 让 source 进来的变量全部导出（进了环境，exec 之后由 node 继承），
                // 然后立刻删掉文件：凭据不再以文件形式留在 chroot 里，只有进程 environ 里有
                // ——那是 dsh 工作必须的，去不掉。
                append("set -a; . ").append(shellQuote(credentials)).append("; set +a; rm -f ")
                    .append(shellQuote(credentials)).append("; ")
            }
            // dsh 每会话落三样东西（实测）：sessions/<项目>/<id>/session.v4.jsonl.zstd、
            // 同目录的 session.lock，以及 storages/session_projcache/sessions/<id>.json
            //（投影缓存，比会话本体大一个数量级）。dsh 从不回收它们，App 侧那份映射表有 128
            // 条上限、dsh 侧没有对应物。只删两周前的文件，碰不到正在续接的会话；删完再收一次
            // 空目录（否则留下满树空壳）；失败（目录不存在等）不阻断启动。
            for (relative in listOf(SESSIONS_RELATIVE, PROJECTION_CACHE_RELATIVE)) {
                append("find ").append(shellQuote(File(rootfsPath, relative).absolutePath))
                    .append(" -type f -mtime +").append(SESSION_RETENTION_DAYS)
                    .append(" -delete 2>/dev/null; ")
            }
            append("find ").append(shellQuote(File(rootfsPath, SESSIONS_RELATIVE).absolutePath))
                .append(" -type d -empty -delete 2>/dev/null; ")
            // dsh 的子进程走 node-pty，需要 /dev/ptmx 与 /dev/pts。运行时的 /dev 是空目录，
            // 不挂进去的话 bash、ripgrep 这类子进程全部起不来（ENOENT / provider failure）。
            // 挂载失败不阻断启动，只是那些工具会报错。
            val devPtsPtmx = File(rootfsPath, "dev/pts/ptmx").absolutePath
            append("if [ ! -e ").append(shellQuote(devPtsPtmx)).append(" ]; then ")
            append("mount --rbind /dev ").append(shellQuote("$rootfsPath/dev")).append(" 2>/dev/null; fi; ")
            // ripgrep 等程序要读 /proc/self/exe（glob 的排序就依赖它）。
            val procSelf = File(rootfsPath, "proc/self").absolutePath
            append("if [ ! -e ").append(shellQuote(procSelf)).append(" ]; then ")
            append("mount -t proc proc ").append(shellQuote("$rootfsPath/proc")).append(" 2>/dev/null; fi; ")
            // 技能库：把 App 的技能目录 bind 进 chroot，dsh 才能读到 SKILL.md，也能把新技能写回来。
            // 先 umount 再 bind，避免上次的挂载残留（挂载失败不阻断启动）。
            val skillsSource = skillsDirectory.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isDirectory }
            if (skillsSource != null) {
                val skillsTarget = File(rootfsPath, SKILLS_TARGET_REL).absolutePath
                append("mkdir -p ").append(shellQuote(skillsTarget)).append("; ")
                append("umount ").append(shellQuote(skillsTarget)).append(" 2>/dev/null; ")
                append("mount --bind ")
                    .append(shellQuote(skillsSource.absolutePath))
                    .append(' ')
                    .append(shellQuote(skillsTarget))
                    .append(" 2>/dev/null; ")
            }
            append("exec chroot ").append(shellQuote(rootfsPath))
            // dsh 0.2 默认通过 native addon 读取 Node 内部模块。Android 上该 .node
            // 会在 initialize 前退出；随包入口已替换为 JS shim，用 Node 自带的
            // --expose-internals 走同一条无 native 依赖路径。
            append(' ').append(DshRuntimeInstaller.NODE_IN_ROOT)
            append(" --expose-internals")
            append(' ').append(DshRuntimeInstaller.DSH_ENTRY_IN_ROOT)
            append(" --profile ").append(ACP_PROFILE)
            if (overlay != null) append(" --patch ").append(shellQuote(overlay))
        }
    }

    /**
     * 把凭据写进 rootfs 里的 0600 脚本，返回它的**宿主**路径（source 发生在 chroot 之前）。
     *
     * 为什么不放进启动命令：`su -c <script>` 的脚本整体是 argv，`ps` 可见。
     * 为什么不只依赖 ProcessBuilder 的 environment：各家 su 对调用方环境的处理不一致，
     * 凭据传丢会变成"能启动、一请求就 401"，比这个窗口更难查。文件落在 App 私有目录里，
     * 权限钉成 owner-only，root 之外读不到。
     */
    private fun writeCredentialEnv(): String? {
        if (apiKey.isBlank() && baseUrl.isBlank()) return null
        return runCatching {
            val file = File(rootfsPath, CREDENTIALS_RELATIVE)
            file.parentFile?.mkdirs()
            file.writeText(
                buildString {
                    if (apiKey.isNotBlank()) {
                        append("export ").append(ENV_API_KEY).append('=')
                            .append(shellQuote(apiKey)).append('\n')
                    }
                    if (baseUrl.isNotBlank()) {
                        append("export ").append(ENV_BASE_URL).append('=')
                            .append(shellQuote(baseUrl)).append('\n')
                    }
                }
            )
            file.setReadable(false, false)
            file.setReadable(true, true)
            file.setWritable(false, false)
            file.setWritable(true, true)
            file.absolutePath
        }.getOrElse { throwable ->
            Log.w(TAG, "credential env write failed", throwable)
            null
        }
    }

    /**
     * 凭据文件写出来了吗。
     *
     * 没写出来就**不能启动**：dsh 会无 key 起来，表现成第一次请求 401，离真正的原因很远。
     * 调用方（[DshAcpRuntime.execute]）在启动前查这个，不满足就明确失败。
     */
    internal fun hasCredentials(): Boolean = File(rootfsPath, CREDENTIALS_RELATIVE).isFile

    /** run 结束时兜底删掉凭据文件：正常由启动脚本 `rm -f`，su 被拒/进程没起来时不会。 */
    internal fun clearCredentialEnv() {
        runCatching { File(rootfsPath, CREDENTIALS_RELATIVE).delete() }
    }

    /**
     * 把 Eta 自己的 MCP 端点声明给 dsh。
     * 端点与 App 内 Agent Loop 共用同一份工具目录，权限检查仍留在 Eta 侧。
     */
    fun mcpServers(): List<JSONObject> {
        val endpoint = AgentToolServerHost.endpoint ?: return emptyList()
        val token = AgentToolServerHost.authToken ?: return emptyList()
        // ACP 的 HTTP MCP 声明必须带 type，且 headers 是 {name,value} 数组而不是对象；
        // 写成对象会被当成 stdio 传输丢掉，agent 于是看不到任何手机工具。
        return listOf(
            JSONObject()
                .put("type", MCP_TRANSPORT_HTTP)
                .put("name", MCP_SERVER_NAME)
                .put("url", endpoint)
                .put(
                    "headers",
                    JSONArray().put(
                        JSONObject().put("name", "Authorization").put("value", "Bearer $token"),
                    ),
                ),
        )
    }

    /**
     * dsh 的 system prompt 后缀：每次运行都重新生成，把当前技能库索引交给它。
     * 装完新技能下一轮自动出现在这里，不需要重启或手动同步。
     */
    private fun personaSuffixLines(): List<String> {
        val lines = ArrayList<String>()
        // 身份写进**运行时**的 system prompt 后缀，而不是只靠 provider 里那份提示词：
        // 后缀每次运行都会重建，所以下一次对话就生效，不依赖 provider 的文本有没有被迁移。
        // 文案取自 AgentIdentity —— 两条活路径共用一份，避免措辞漂移。
        lines += AgentIdentity.ROLE_LINE
        lines += ""
        lines += "Your working directory is {{cwd}}."
        lines += ""
        lines += "技能库：dsh 自带技能在 /root/.dsh/skills；Heta 技能库在 $SKILLS_IN_ROOT（每个技能是 <名字>/SKILL.md，由 Heta App 管理）。"
        lines += "任务与某个技能相符时，先读对应技能的 SKILL.md 再执行；需要新技能时用 skills_list_curated / skills_inspect_github 找到，"
        lines += "再通过 skills_install_from_github 安装——装好后会同步进 Heta 技能库并自动可用。"
        val skills = skillIndex()
        if (skills.isEmpty()) {
            lines += "当前技能库为空。"
        } else {
            lines += "Heta 技能库现有技能："
            skills.take(MAX_SKILL_LINES).forEach { (name, description) ->
                lines += if (description.isBlank()) "- $name" else "- $name：$description"
            }
            if (skills.size > MAX_SKILL_LINES) {
                lines += "（还有 ${skills.size - MAX_SKILL_LINES} 个，完整列表用 skills_list 查看）"
            }
        }
        return lines
    }

    /** 从技能目录读出名字与一句话描述；SKILL.md 缺失或读不动的技能跳过。 */
    private fun skillIndex(): List<Pair<String, String>> {
        val directory = skillsDirectory.takeIf { it.isNotBlank() }?.let(::File) ?: return emptyList()
        val entries = directory.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: return emptyList()
        return entries.mapNotNull { skill ->
            val document = File(skill, "SKILL.md").takeIf { it.isFile } ?: return@mapNotNull null
            val text = runCatching { document.readText().take(4_000) }.getOrNull() ?: return@mapNotNull null
            val name = frontmatterValue(text, "name")?.takeIf { it.isNotBlank() } ?: skill.name
            val description = frontmatterValue(text, "description")?.trim()?.take(MAX_SKILL_DESCRIPTION).orEmpty()
            name to description
        }
    }

    private fun frontmatterValue(text: String, key: String): String? {
        val lines = text.lineSequence().take(40).toList()
        if (lines.firstOrNull()?.trim() != "---") return null
        for (index in 1 until lines.size) {
            val line = lines[index].trim()
            if (line == "---") break
            if (line.startsWith("$key:")) return line.removePrefix("$key:").trim()
        }
        return null
    }

    companion object {
        private const val SU = "su"
        private const val ACP_PROFILE = "acp"
        private const val DEFAULT_ROUTE = "deepseek-official"

        private const val PERMISSION_MODE = "danger-full-access"
        private const val TAG = "DshRuntimeConfig"

        /**
         * dsh 的 ACP profile 自带的 persona 前缀，逐字抄自 `dsh-acp-app/cordis.patch.yml`。
         *
         * 之所以要在这里重写一遍：`--patch` 覆盖层的 `config` 是整块替换，只写 personaSuffix
         * 会把这一句静默抹掉（`dsh --profile acp --dump-config` 里 personaPrefix 直接消失）。
         * dsh 换了这句文案时这里会滞后，但滞后只影响「模型看到的前缀措辞」。
         */
        private const val PERSONA_PREFIX = "You are a coding agent powered by the {{model}} model."
        /** 前段是宿主的 Android 路径（su、chroot），后段是 chroot 内的路径（node）。 */
        private const val PATH_IN_ROOT =
            "/system/bin:/system/xbin:/product/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        private const val ENV_API_KEY = "DEEPSEEK_API_KEY"
        private const val TMP_IN_ROOT = "/tmp"
        private const val ENV_BASE_URL = "DEEPSEEK_BASE_URL"
        private const val MCP_SERVER_NAME = "heta"
        private const val MCP_TRANSPORT_HTTP = "http"
        private const val OVERLAY_RELATIVE = "opt/dsh/heta-run-overlay.patch.yml"
        private const val OVERLAY_IN_ROOT = "/opt/dsh/heta-run-overlay.patch.yml"
        private const val CREDENTIALS_RELATIVE = "opt/dsh/heta-run-env.sh"
        /** dsh 自己的会话目录；App 侧那份有 128 条上限，这份没有，只能按年龄清。 */
        private const val SESSIONS_RELATIVE = "root/.dsh/sessions"
        /** dsh 的会话投影缓存，和会话目录一起长，且比会话本体大得多。 */
        private const val PROJECTION_CACHE_RELATIVE = "root/.dsh/storages/session_projcache"
        private const val SESSION_RETENTION_DAYS = 14
        private const val SKILLS_TARGET_REL = "root/.agents/skills"
        private const val SKILLS_IN_ROOT = "/root/.agents/skills"
        private const val MAX_SKILL_LINES = 40
        private const val MAX_SKILL_DESCRIPTION = 160

        /** 自定义网关模型的目录窗口大小；随包默认目录由生成文件维护。 */
        private const val DEFAULT_CONTEXT_WINDOW = 1_000_000

        /**
         * 内置运行时尚未展开时返回 null；调用方会给出明确原因并结束本次执行，
         * 不会回退到旧内核。
         */
        fun resolveBuiltin(
            context: Context,
            providerRoute: String,
            model: String,
            apiKey: String,
            baseUrl: String,
        ): DshRuntimeConfig? {
            if (!DshRuntimeInstaller.isReady(context)) return null
            runCatching { SkillRuntime.skillsRoot(context).mkdirs() }
            return DshRuntimeConfig(
                rootfsPath = DshRuntimeInstaller.runtimeDirectory(context).absolutePath,
                providerRoute = providerRoute,
                model = model,
                apiKey = apiKey,
                baseUrl = baseUrl,
                skillsDirectory = SkillRuntime.skillsRoot(context).absolutePath,
            )
        }

        private fun shellQuote(value: String): String =
            "'" + value.replace("'", "'\\''") + "'"
    }
}
