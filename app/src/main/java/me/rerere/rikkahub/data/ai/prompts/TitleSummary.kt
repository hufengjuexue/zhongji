package me.rerere.rikkahub.data.ai.prompts

internal val DEFAULT_TITLE_PROMPT = """
    I will give you some dialogue or story excerpt in the `<content>` block.
    Summarize the conversation or scene into a short, evocative title.
    1. Keep the title language consistent with the primary language of the content ({locale} if ambiguous).
    2. Reply directly with only the title (no quotes or prefixes).
    3. Keep it concise (typically under 20 characters in CJK or 6 words in English).

    <content>
    {content}
    </content>
""".trimIndent()
