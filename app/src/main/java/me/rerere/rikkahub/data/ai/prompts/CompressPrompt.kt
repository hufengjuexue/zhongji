package me.rerere.rikkahub.data.ai.prompts

/**
 * 压缩指令，作为一条 user 消息追加在对话末尾，模型直接总结上方的对话历史。
 * 摘要会替代之前的全部历史，按固定的结构输出，避免遗漏继续对话需要的信息。
 */
internal fun buildCompressPrompt(
    additionalPrompt: String,
    locale: String,
): String = buildString {
    appendLine(
        """
        Stop and write a structured continuity summary of the conversation or roleplay so far. This summary will replace the older turns as the working memory for continuing the interaction seamlessly, so preserve every concrete detail needed to stay consistent.

        Do not call any tools. Respond with the summary only, using exactly the following Markdown headings. Omit a section if it does not apply.

        [Summary of previous conversation]

        ## Core Relationship & Dynamic
        Who the user and character/assistant are to each other, current relationship stage, emotional tone, dynamic, and any established persona rules or boundaries.

        ## Story & Key Events
        Chronological plot beats, meaningful shared experiences, decisions made, and turning points (quote exact names, places, and key dialogue/outcomes).

        ## Promises, Secrets & Foreshadowing
        Active vows, agreements, revealed or hidden secrets, unresolved mysteries, debts, and subtle clues planted for later turns.

        ## Scene & Character States
        Current location, time/atmosphere, physical & emotional state, attire/injuries/notable items held, plus concrete user facts or constraints still in effect.

        ## Open Threads & Current Moment
        What was happening immediately before this summary, unfinished conversations or actions, and the exact narrative beat to continue from.

        Requirements:
        - Keep the section headings as given above, but write the content in the same language as the conversation; if unclear, use $locale
        - Be vivid, specific, and concrete; preserve exact names, phrasing, and sensory/emotional details rather than vague abstractions
        - Maintain narrative/canon fidelity; do not summarize out of character with meta-critiques
        - Do not add any explanation or commentary outside the summary
        """.trimIndent()
    )
    if (additionalPrompt.isNotBlank()) {
        appendLine()
        appendLine("Additional instructions from user: $additionalPrompt")
    }
}.trimEnd()
