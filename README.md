<div align="center">

<img src="docs/app-icon.png" width="96" alt="Kairos Icon"/>

# Kairos

> **Kairos (Ancient Greek: καιρός)** — Unlike *Chronos* (linear, mechanical clock time), *Kairos* stands for "the opportune, pivotal moment." The right memory surfaces at the exact turn it matters, rather than drowning the prompt in static bloat.

### Android-Native Immersive AI Roleplay Client

**Pure Native Compose UI · Prompt Cache Protection · Tail Vector Recall · Temporal Relation Graph**

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
* **Temporal Relation Graph**: Built-in SQLite entity-relation graph (`recall_relations` / `graph_upsert_relations`) tracks characters, factions, locations, items, and vows with `valid_from` / `valid_to` / `is_current` lifecycle fields — preserving past states chronologically rather than overwriting history.
* **Pre-Compression Extraction & Visual Graph Tab**: Automatically salvages unrecorded facts and relation shifts right before context compression collapses older turns, with an interactive Compose network canvas and current-vs-past timeline inspector inside Assistant Memory.

### 2. SillyTavern Card Compatibility
* **V2 / V3 PNG & JSON Import**: Automatically restores character avatars, system prompts, first greetings, alternate greetings, `mes_example`, and `post_history_instructions`.
* **Embedded Lorebook & Regex Unpacking**: Automatically unpacks `character_book` (array or object entries) into Lorebooks and converts `extensions.regex_scripts` (normalizing `/pattern/flags`) into message regex rules.

### 3. Native Android Architecture
* Direct connection to modern frontier providers (Anthropic, Google, OpenAI, DeepSeek, Grok, and OpenAI-compatible endpoints) plus configurable Embedding models.
* Independent package identity (`me.kairos.app`) with Adaptive Icon + Monochrome support (`mipmap-anydpi-v26`).

---

## Modern RP Philosophy & Three-Model Collaboration

> **Experience = Logic Baseline × Language Granularity** — Modern frontier models already know how to roleplay. Instead of ten-thousand-word jailbreak boilerplate and sampler-slider mysticism, give them a clean prefix cache and surgical long-term recall.

### 1. Modern RP: Source Decoupling & Prefix Cache First
* **Zero Bloat, Pure Signal**: Drops archaic sampler-slider clutter and heavy webview wrappers, decoupling lore and long-term state into local **Source (Markdown memory workspace)** files and a **Room temporal relation graph**.
* **Byte-Stable Prefix**: System prompts, character cards, and core profile anchors stay pinned and byte-stable at the prefix, maximizing cloud Prompt Caching hit rates across long sessions.

### 2. Three-Model Collaboration (`Chat × Embedding × Decision`)
Kairos splits real-time storytelling and long-term memory across three focused model roles:
1. **01 · Primary Chat Model**: Drives narrative continuity, persona logic, prose texture, and tool invocation.
2. **02 · Embedding Model (Stage 1 Coarse Recall)**: Encodes `topics/*.md` and out-of-window historical turns into a compact local `VEC1` binary index (with automatic lexical fallback when unconfigured) for millisecond candidate retrieval.
3. **03 · Decision / Rerank Model (Stage 2 Precision Filtering)**: Uses `gemini-decision-1` (`POST /v1beta/decisions` single-forward batch scoring) or a fast non-thinking reranker to distill candidates into Top-K relevance, injected by `MemoryVectorTailTransformer` **only right before the latest `USER` turn** so prefix Prompt Caching remains 100% intact.

### 3. Focused on RP, Yet Ready as a Lightweight Agent
While laser-focused on immersive roleplay, **Kairos** cleanly retains **RikkaHub**'s practical Agent foundation—**MCP tool integration**, **Agent Skills**, **PRoot Ubuntu / SSH workspace file & terminal access**, and **web search**—giving you capable lightweight Agent workflows without shipping bloated multi-hundred-MB local runtimes.

---

## Quick Start

1. **Install**: Download the latest `kairos-*.apk` from [Releases](https://github.com/hufengjuexue/kairos/releases).
2. **Configure the Trio**: Open **Settings → Model Settings** to set your **Chat Model**, and optionally pick your **Embedding Model** (Stage 1) and **Decision / Rerank Model** (Stage 2).
3. **Import Character**: Import any V2/V3 PNG or JSON character card and start talking.

---

## Heritage & License

Built with respect on top of open-source foundations:
* **[RikkaHub](https://github.com/rikkahub/rikkahub)** for the Android Jetpack Compose UI and AI provider infrastructure.
* **[Huadeng](https://github.com/MiaoWuNYA/rikkahub-sillytavern-android)** & **[LastChat](https://github.com/Cocolalilal/LastChat)** for pioneering mobile SillyTavern protocol compatibility and tail memory ideas.

Licensed under the [AGPL-3.0 License](LICENSE).
