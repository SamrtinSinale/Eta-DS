package io.github.mangi.eta.agent.dsh

/**
 * `cordis.patch.yml` 的**保注释**改写：纯文本，不碰文件系统。
 *
 * 为什么不用 YAML 库（实测结论，不是偏好）：
 *   · dsh 的补丁层是带扩展标签的 YAML：`dsh-base/cordis.patch.yml` 里就有
 *     `disabled: !!js "!ctx.get('profileContext')"`，而 **snakeyaml 2.4 拒绝加载**这种文件
 *     （`Global tag is not allowed: tag:yaml.org,2002:js`）。官方为此专门注册了 customTags
 *     —— 见 `dsh-plugin-manager` 的 `writePluginEnabled`。
 *   · 就算加载得动，序列化回去也会**丢注释、重排缩进**（`personaPrefix: >-` 这类折叠标量会被摊平）。
 *     我们改的是**用户自己的**补丁层，不能顺手把人家文件重排一遍。
 *   ⇒ 所以这里只做逐行外科手术：只动目标条目的 `disabled` 那一行，其余字节原样保留。
 *
 * 与官方实现的对应关系（都以 `dsh-plugin-manager` 的 `writePluginEnabled` 为准）：
 *   · 顶层必须是序列（官方对非序列直接 throw）；我们返回 [DshPatchEdit.Rejected]，因为界面要显示原因；
 *   · 找目标用**最后一个**匹配项（官方 `findLast`），配合"同一 id 后写的覆盖先写的"这条语义；
 *   · 带 `insert:` 的条目**不是**开关目标（官方 `!item.has("insert")`）；
 *   · 启用时写 `disabled: false`，而不是把这个键删掉（官方 `setIn(..., !enabled)`）；
 *   · 条目上写了 `name:` 且与调用方给的模块名不同 → 不算匹配（官方 `!expectedName || expectedName === name`）；
 *   · 官方落盘是 `writeFileAtomic(..., mode 0o600)`；原子替换在 [DshProfileStore] 里做。
 *
 * 已知**故意**的差异（都不改变 dsh 的读法，只是宁可拒绝也不猜）：
 *   · 流式写法（`- {id: x}`）与 CRLF 换行：拒绝写；
 *   · `disabled` 键下面还挂着嵌套内容：拒绝写（只替换那一行会留下孤儿行）。
 */
internal class DshPatchDocument private constructor(private val patch: Patch) {

    /** 形状不认识时的原因（null = 认识，可以改）。 */
    val rejection: String? get() = patch.rejection

    /**
     * 补丁层里能寻址到的行，按文档顺序。
     *
     * 含 `insert:` 里的**嵌套**行（官方列插件时也是 `flatten` 到这一层的），但不含那些
     * 只用来分组的 `insert` 条目 —— 它们的 id 是组名，不是插件。
     */
    val rows: List<DshPatchRow> get() = patch.rows

    /**
     * 把 [patchId] 那一行的 `disabled` 写成 [disabled]。
     *
     * [moduleName] 是调用方认为该行声明的模块名：条目自己写了 `name:` 且与之不符时不匹配（官方同此），
     * 于是在文件末尾**追加一条覆盖行** —— 这正是"开关一个 bundle 自带的插件"的写法：bundle 的补丁层
     * 我们从不改，覆盖行写在 profile 层、排在最后，于是生效。
     */
    fun withDisabled(patchId: String, moduleName: String?, disabled: Boolean): DshPatchEdit {
        patch.rejection?.let { return DshPatchEdit.Rejected(it) }
        if (patchId.isBlank()) return DshPatchEdit.Rejected("行 id 是空的")

        val target = patch.topLevel.lastOrNull { item ->
            item.id == patchId && !item.hasInsert && (item.name == null || item.name == moduleName)
        }

        if (target != null) {
            val current = target.disabled
            if (current is DisabledKey.Literal && current.value == disabled) return DshPatchEdit.Unchanged
            if (current is DisabledKey.Block) {
                return DshPatchEdit.Rejected("`$patchId` 的 disabled 键下面还挂着内容，不敢只改那一行")
            }
            val edited = patch.lines.toMutableList()
            val setting = "disabled: $disabled"
            val at = target.disabledLine
            if (at != null) {
                val old = patch.lines[at]
                // 保留该行原有的缩进与行尾注释，只换值。
                val comment = old.substring(stripComment(old).length).trim()
                edited[at] = indentOf(old) + setting + if (comment.isEmpty()) "" else "  $comment"
            } else {
                val indent = span(target.keyIndent)
                edited.add(target.dashLine + 1, indent + setting)
            }
            return DshPatchEdit.Changed(render(edited))
        }

        val id = yamlScalar(patchId)
            ?: return DshPatchEdit.Rejected("行 id 里有换行或控制字符，写不进 YAML：$patchId")
        val edited = patch.lines.toMutableList()
        val empty = patch.emptyFlowLine
        // 追加的缩进要跟这份文件已有的根序列对齐（`[]` 那行也可能是缩进过的）。
        val base = if (empty != null) indentOf(patch.lines[empty]).length else patch.rootIndent
        val block = listOf(span(base) + "- id: $id", span(base + 2) + "disabled: $disabled")
        if (empty != null) {
            // 空序列 `[]` 后面直接接 `- ` 会变成非法 YAML（一个文档不能有两个根节点），
            // 所以是**替换**那一行，不是追加。
            edited[empty] = block[0]
            edited.add(empty + 1, block[1])
        } else {
            edited.addAll(block)
        }
        return DshPatchEdit.Changed(render(edited))
    }

    companion object {
        /** 官方在补丁文件不存在时用的内容（`readFile` ENOENT → `"[]\n"`）。 */
        const val EMPTY: String = "[]\n"

        fun parse(text: String): DshPatchDocument = DshPatchDocument(scan(text))
    }
}

/** 一次改写的结局。 */
internal sealed interface DshPatchEdit {

    /** 文件本来就是想要的样子，一个字都不用写。 */
    data object Unchanged : DshPatchEdit

    /** 改好了；[text] 是新的完整内容。 */
    data class Changed(val text: String) : DshPatchEdit

    /** 拒绝改：[reason] 给界面显示；文件一个字都没动。 */
    data class Rejected(val reason: String) : DshPatchEdit
}

/** 补丁行里 `disabled` 的值。三态是**故意的**：说不清就得说说不清。 */
internal enum class DshRowState {

    /** 没有 `disabled`，或它是明确的 `false`。 */
    ENABLED,

    /** `disabled` 明确是 `true`。 */
    DISABLED,

    /**
     * `disabled` 不是字面布尔 —— `!!js` 表达式、`&anchor`、引号包起来的 `"true"`、`disabled:` 挂块。
     * dsh 那边怎么判真我们没实证（引号包起来的就是**字符串**，不是布尔），所以不替它下结论：
     * 界面要把这类行显示成"由表达式决定"，不能假装知道开关状态。
     */
    UNRESOLVED,
}

/** 补丁层里一个能寻址的行。 */
internal data class DshPatchRow(
    /** 补丁里的行 id（官方 `patchId`）—— 开关就是按它定位。 */
    val patchId: String,
    /** 该行声明的模块名（官方 `moduleName`）；只做覆盖的行常常不写。 */
    val moduleName: String?,
    val state: DshRowState,
)

/** 解析结果（纯数据）。 */
private class Patch(
    val lines: List<String>,
    val items: List<Item>,
    val topLevel: List<Item>,
    val emptyFlowLine: Int?,
    val rejection: String?,
    val rows: List<DshPatchRow>,
    /** 顶层条目里那个 `-` 所在的列（正常的补丁文件是 0，缩进的根序列也认）。 */
    val rootIndent: Int,
)

/**
 * 文档里的一个序列条目。
 *
 * [dashIndent] 是那个 `-` 所在的列，[keyIndent] 是它自己那些键所在的列（`- id: x` 就是 2）。
 * 只有 [keyIndent] 那一层的键属于它 —— 更深的是键的值（`config:` 的内容等），不算它的键。
 */
private class Item(val dashLine: Int, val dashIndent: Int) {
    var keyIndent: Int = -1
    var id: String? = null
    var name: String? = null
    var disabledLine: Int? = null
    var disabled: DisabledKey = DisabledKey.Absent
    var hasInsert: Boolean = false
}

private sealed interface DisabledKey {
    data object Absent : DisabledKey
    data class Literal(val value: Boolean) : DisabledKey

    /** 说得出、但不是布尔（`!!js`、引号、`yes` 之类）。 */
    data object NonLiteral : DisabledKey

    /** `disabled:` 后面是空值，内容在下一层。 */
    data object Block : DisabledKey
}

/** 合法到能直接写成 YAML 键值的形状（别的就加引号，再加不了就拒绝）。 */
private val PLAIN_SCALAR = Regex("^[A-Za-z0-9_$.@/\\-]+$")

/** 会被 YAML 解析成非字符串的裸标量（`- id: true` 会让 id 变成布尔）。 */
private val RESERVED_SCALAR = Regex("^(true|false|null|~|-?\\d+(\\.\\d+)?)$", RegexOption.IGNORE_CASE)

private fun scan(text: String): Patch {
    val empty = emptyList<Item>()
    if (text.contains('\r')) {
        return Patch(
            lines = emptyList(),
            items = empty,
            topLevel = empty,
            emptyFlowLine = null,
            rejection = "补丁文件用的是 CRLF 换行：不敢改，怕把行尾写坏",
            rows = emptyList(),
            rootIndent = 0,
        )
    }

    val lines = when {
        text.isBlank() -> emptyList()
        text.endsWith("\n") -> text.dropLast(1).split("\n")
        else -> text.split("\n")
    }
    val items = ArrayList<Item>()
    val open = ArrayList<Item>()
    var rejection: String? = null
    var emptyFlowLine: Int? = null

    for (index in lines.indices) {
        val raw = lines[index]
        val body = raw.trimStart(' ')
        if (body.isEmpty() || body.startsWith("#")) continue
        if (body[0] == '\t') {
            rejection = "补丁文件里有制表符缩进：这不是合法 YAML 缩进，不敢改"
            continue
        }
        val indent = raw.length - body.length

        // 块序列的指示符后面必须有空格，`-id` 是个普通标量，不是条目。
        if (body.startsWith("-") && (body.length == 1 || body[1] == ' ')) {
            while (open.isNotEmpty() && open.last().dashIndent >= indent) {
                open.removeAt(open.size - 1)
            }
            val item = Item(dashLine = index, dashIndent = indent)
            val rest = body.substring(1)
            if (rest.isNotBlank()) {
                val content = rest.trimStart(' ')
                item.keyIndent = indent + 1 + (rest.length - content.length)
                if (content.startsWith("{")) {
                    rejection = "补丁里有流式映射（`- {…}`）：不敢改，怕把写法弄坏"
                } else {
                    readKey(item, content, index)
                }
            }
            items += item
            open += item
            continue
        }

        val owner = open.lastOrNull { it.keyIndent == indent }
            ?: open.lastOrNull { it.keyIndent < 0 && indent > it.dashIndent }?.also { it.keyIndent = indent }
        if (owner != null) {
            readKey(owner, body, index)
            continue
        }
        if (open.isEmpty() && items.isEmpty() && body == "[]") {
            emptyFlowLine = index
            continue
        }
        // 没有任何一层条目能装下这一行 ⇒ 它不是补丁序列的成员（顶层是映射，或者序列外面混了映射）。
        // 注意判据是 `none { dashIndent < indent }` 而不是比较最后一个条目：`- insert:` 后面接
        // 兄弟键（`  foo: bar`）是合法的，只比 innermost 会把这种正当写法误判成坏文件。
        if (open.none { it.dashIndent < indent }) {
            rejection = if (items.isEmpty()) {
                "顶层不是补丁序列（这份文件不是以 `-` 开头的补丁列表），不敢改"
            } else {
                "顶层不是补丁序列（序列外面又出现了映射），不敢改"
            }
            continue
        }
        // 条目内部更深的内容（`config:` 的值、`insert:` 的列表…）：不关我们的事。
    }

    if (rejection == null && emptyFlowLine != null && items.isNotEmpty()) {
        rejection = "补丁里既有空的 `[]` 又有条目，这不是一份能读的 YAML，不敢改"
    }

    val rootIndent = items.minOfOrNull { it.dashIndent } ?: 0
    val topLevel = items.filter { it.dashIndent == rootIndent }
    val rows = items.mapNotNull { item ->
        val id = item.id?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        // 带 insert 的条目是**分组**，它的 id 不是插件（官方开关时也排除这类）。
        if (item.hasInsert) return@mapNotNull null
        DshPatchRow(patchId = id, moduleName = item.name, state = item.disabled.state())
    }
    return Patch(
        lines = lines,
        items = items,
        topLevel = topLevel,
        emptyFlowLine = emptyFlowLine,
        rejection = rejection,
        rows = rows,
        rootIndent = rootIndent,
    )
}

private fun readKey(item: Item, content: String, line: Int) {
    val colon = content.indexOf(':')
    if (colon <= 0) return
    val key = content.substring(0, colon).trim()
    if (!PLAIN_SCALAR.matches(key)) return
    val value = content.substring(colon + 1)
    when (key) {
        "id" -> item.id = stringValue(value)
        "name" -> item.name = stringValue(value)
        "disabled" -> {
            item.disabled = disabledValue(value)
            item.disabledLine = line
        }
        "insert" -> item.hasInsert = true
    }
}

private fun DisabledKey.state(): DshRowState = when (this) {
    DisabledKey.Absent -> DshRowState.ENABLED
    DisabledKey.Block, DisabledKey.NonLiteral -> DshRowState.UNRESOLVED
    is DisabledKey.Literal -> if (value) DshRowState.DISABLED else DshRowState.ENABLED
}

/** 取标量文本；`null` = 这里没有可当字符串用的值（空值、标签、锚点）。 */
private fun stringValue(raw: String): String? {
    val text = stripComment(raw).trim()
    if (text.isEmpty()) return null
    if (text.startsWith("!!") || text.startsWith("&") || text.startsWith("*")) return null
    return unquote(text)
}

private fun disabledValue(raw: String): DisabledKey {
    val text = stripComment(raw).trim()
    if (text.isEmpty()) return DisabledKey.Block
    if (text.startsWith("!!") || text.startsWith("&") || text.startsWith("*")) return DisabledKey.NonLiteral
    // 引号包起来的布尔在 YAML 里是**字符串**，不是布尔 —— 不敢替 dsh 判它的真假。
    if (text.startsWith("\"") || text.startsWith("'")) return DisabledKey.NonLiteral
    return when (text) {
        "true", "True", "TRUE" -> DisabledKey.Literal(true)
        "false", "False", "FALSE" -> DisabledKey.Literal(false)
        else -> DisabledKey.NonLiteral
    }
}

/** 去掉行尾注释（认引号，避免把 `'a#b'` 里的 `#` 当注释）。 */
private fun stripComment(raw: String): String {
    var quote = ' '
    for (index in raw.indices) {
        val char = raw[index]
        when {
            quote == ' ' && (char == '\'' || char == '"') -> quote = char
            quote != ' ' && char == quote -> quote = ' '
            quote == ' ' && char == '#' && (index == 0 || raw[index - 1].isWhitespace()) ->
                return raw.substring(0, index)
        }
    }
    return raw
}

private fun unquote(text: String): String = when {
    text.length >= 2 && text.startsWith("\"") && text.endsWith("\"") ->
        text.substring(1, text.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
    text.length >= 2 && text.startsWith("'") && text.endsWith("'") ->
        text.substring(1, text.length - 1).replace("''", "'")
    else -> text
}

private fun indentOf(line: String): String = span(line.length - line.trimStart(' ').length)

private fun span(width: Int): String = " ".repeat(width.coerceAtLeast(0))

/** 写回 YAML 的标量：能裸写就裸写，必要时加单引号，加不了就 `null`（拒绝）。 */
private fun yamlScalar(id: String): String? {
    if (id.any { it == '\n' || it == '\r' || it == '\t' || it.code < 0x20 }) return null
    if (PLAIN_SCALAR.matches(id) && !RESERVED_SCALAR.matches(id)) return id
    return "'" + id.replace("'", "''") + "'"
}

/** 改完的文件一律以换行结尾（官方 `String(document)` 也是）。 */
private fun render(lines: List<String>): String = lines.joinToString("\n") + "\n"
