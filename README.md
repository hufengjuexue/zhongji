<div align="center">

<img src="docs/app-icon.png" width="96" alt="Kairos Icon"/>

# Kairos

### Android-Native Immersive AI Roleplay Client

**Pure Native Compose UI · Prompt Cache Protection · Tail Vector Memory Recall · Sovereign RP Core**

[**简体中文**](README_CN.md) | [**English**](README.md)

[![Release](https://img.shields.io/github/v/release/hufengjuexue/kairos?label=Release&color=brightgreen)](https://github.com/hufengjuexue/kairos/releases)
[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](https://github.com/hufengjuexue/kairos/releases)
[![License](https://img.shields.io/badge/License-AGPL--3.0-blue)](LICENSE)

</div>

---

## Philosophy

**Kairos** separates the UI surface from the roleplay brain:
* **Pure Native Compose Frontend**: Preserves the clean, tactile Android-native interaction and typography lineage of RikkaHub — no heavy WebView wrapper, no bloated overlays, no sampler slider sprawl.
* **Prompt Cache First**: Everything at the prefix (system prompt, permanent profile/preferences, non-volatile memory listing) stays byte-stable across turns to maximize Anthropic/provider Prompt Cache hits.
* **Tail Recency Recall (`MemoryVectorTailTransformer`)**: Long-term memory files (`topics/*.md`) and overflowed early conversation chunks are recalled on demand via cloud Embedding similarity (with automatic local lexical fallback) and injected strictly before the final `USER` turn.

---

## Core Capabilities

### 1. Memory & Context Pipeline
* **Unshackled Memory Prompts**: Removes productivity-assistant prohibitions against emotional continuity, romance, and persona immersion while retaining basic real-world privacy boundaries.
* **Prefix-Safe Memory Listing**: Keeps the system memory directory stable without mutable daily date stamps that break Prompt Caching.
* **Hybrid Tail Injection**: Recalls relevant chunks from both `topics/*.md` and conversation turns truncated by context limits or compaction, freezing recalled context across multi-step tool calls within the same turn.
* **Compact Binary Vector Cache (`VEC1`)**: Stores normalized Float32 embeddings cleanly on disk with backward compatibility for legacy `.vec` files and lazy LRU-style pruning.

### 2. SillyTavern Card Compatibility
* **V2 / V3 PNG & JSON Import**: Automatically restores character avatars, system prompts, first greetings, `mes_example`, and `post_history_instructions`.
* **Embedded Lorebook & Regex Unpacking**: Automatically unpacks `character_book` (array or object entries) into Lorebooks and converts `extensions.regex_scripts` (normalizing `/pattern/flags`) into message regex rules.

### 3. Native Android Architecture
* Direct connection to modern frontier providers (Anthropic, Google, OpenAI, DeepSeek, Grok, and OpenAI-compatible endpoints) plus configurable Embedding models.
* Independent package identity (`me.kairos.app`) with Adaptive Icon + Monochrome support (`mipmap-anydpi-v26`).

---

## Quick Start

1. **Install**: Download the latest `kairos-*.apk` from [Releases](https://github.com/hufengjuexue/kairos/releases).
2. **Configure Models**: Open **Settings → Models**, configure your primary chat model and (optionally) an Embedding model under **General → Vector Storage**.
3. **Import Character**: Import any V2/V3 PNG or JSON character card and start talking.

---

## Heritage & License

Built with respect on top of open-source foundations:
* **[RikkaHub](https://github.com/rikkahub/rikkahub)** for the Android Jetpack Compose UI and AI provider infrastructure.
* **[Huadeng](https://github.com/MiaoWuNYA/rikkahub-sillytavern-android)** & **[LastChat](https://github.com/Cocolalilal/LastChat)** for pioneering mobile SillyTavern protocol compatibility and tail memory ideas.

Licensed under the [AGPL-3.0 License](LICENSE).
