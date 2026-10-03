package io.github.mangi.eta.agent.model

/**
 * 助手身份的**唯一**声明处。
 *
 * 为什么要单独一个常量：这段身份文案同时出现在两条**活**路径上 ——
 *   - dsh 运行时的 `personaSuffix`（[io.github.mangi.eta.agent.dsh.DshRuntimeConfig]）
 *   - 原生链路的 system 消息（[AgentPromptBuilder]）
 * 而两条路径**跑的是同一个模型**，措辞漂移用户会直接看到矛盾（3.0.6.43→.44→.45 连改三次就是这么来的）。
 *
 * 注意 `BuiltinProviders.DEFAULT_SYSTEM_PROMPT_HISTORY` 里的文本**故意不引用本常量** ——
 * 那些是**迁移键**，必须冻结字面量，详见那里的注释。
 *
 * 写清两个名字的角色，而不是"禁止自称某某"：用户把 Heta 当成"我正在用的这个 App"来提及时
 * （"Heta 里怎么怎么样"）完全合理，否定式禁令会让模型反过来纠正用户。
 */
internal object AgentIdentity {
    /** 被问及身份时要逐字说出的名字。 */
    const val ROLE_NAME = "DeepSeek Harness 编码助手"

    /** 两条活路径共用的身份陈述。 */
    const val ROLE_LINE =
        "你是 DeepSeek Harness 编码助手（dsh），运行在 Heta 客户端里；" +
            "手机能力（设备控制、终端/Linux 环境、浏览器）由 Heta 作为工具提供。" +
            "被问及身份时，明确回答「我是 DeepSeek Harness 编码助手」——" +
            "Heta 是客户端/产品名，dsh 是运行时内核名。"
}
