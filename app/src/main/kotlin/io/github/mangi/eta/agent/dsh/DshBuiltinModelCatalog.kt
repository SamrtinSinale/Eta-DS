package io.github.mangi.eta.agent.dsh

/**
 * Generated from the bundled @deepseek-ai/dsh-llm-deepseek DEFAULT_MODELS.
 *
 * Do not edit by hand. scripts/build-dsh-runtime.py --check compares this file
 * with dsh-runtime.tar.xz and fails when they drift.
 */
internal object DshBuiltinModelCatalog {
    val IDS: List<String> = listOf(
        "deepseek-flash",
        "deepseek-v4-pro",
    )

    val YAML: String = """
        - id: "deepseek-flash"
          name: "DeepSeek-V41-Flash"
          contextWindow: 1000000
          systemPromptUpdate: in-history
          toolUpdate: addition-only
          inputModalities: [text, image]
        - id: "deepseek-v4-pro"
          name: "DeepSeek-V4-Pro"
          description: "Stronger agentic coding, knowledge, and difficult reasoning; suited to complex or quality-critical tasks at higher cost."
          contextWindow: 1000000
    """.trimIndent().prependIndent("      ")
}
