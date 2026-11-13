package me.rerere.rikkahub.data.ai.prompts

internal val DEFAULT_SUGGESTION_PROMPT = """
    I will provide recent conversation turns inside the `<content>` block.
    Act as the **User** continuing this conversation or scene naturally, generating 3~5 short, distinct, in-character or contextually fitting reply options.

    Rules:
    1. Reply directly with the suggestions, separated by newlines, without numbering, bullets, or extra commentary.
    2. Use {locale} language (or match the active scene language if clearer).
    3. Keep each suggestion concise (ideally under 15 words / 25 characters) and playable immediately.
    4. Match the user's tone, persona, pacing, and narrative style.
    5. Speak only as the User, never break character or speak as the Assistant.

    <content>
    {content}
    </content>
""".trimIndent()
