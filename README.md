<div align="center">

<img src="docs/app-icon.png" width="96" alt="ZhongJi RP Icon"/>

# 终极 RP · ZhongJi RP

### Android-Native Immersive AI Roleplay Client Rebuilt for Frontier LLMs

**Instant Immersive Flow · Lossless SillyTavern Compatibility · On-Device Vector Tail Injection · Deep Prompt Caching Protection**

[**简体中文**](README_CN.md) | [**English**](README.md)

[![Release](https://img.shields.io/github/v/release/hufengjuexue/zhongji?label=Release&color=brightgreen)](https://github.com/hufengjuexue/zhongji/releases)
[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](https://github.com/hufengjuexue/zhongji/releases)
[![License](https://img.shields.io/badge/License-AGPL--3.0-blue)](LICENSE)

</div>

---

## 🌟 Why ZhongJi RP?

**The traditional AI RP ecosystem has long been trapped in an era of "desperate compromise engineering":**
* **Legacy SillyTavern** was originally designed for ancient 7B / 13B local models. To force coherence out of weak models, it accumulated hundreds of sampler sliders, ten-thousand-word jailbreak templates, and anti-proxy impersonation incantations, while forcing mobile users to wrestle with Termux, Node.js, or keep home PCs running local reverse tunnels. **These were historical compromises, not modern innovation.**
* **Conventional Chat Apps**, while lightweight, completely lack character depth. They suffer from rapid context amnesia, cannot parse character cards or lorebooks, and fail to sustain deep, nuanced roleplay.

**Modern Frontier LLMs already possess high-dimensional semantic understanding and emotional resonance. They no longer need archaic walls of prompt text to "teach the AI how to act."**

> 💡 **The Core Philosophy of ZhongJi RP: Subtraction and Perfection.**  
> Purge obsolete compromise engineering and cumbersome barriers so that even a complete beginner can jump straight into the story; distill and re-engineer truly essential, high-performance architecture to unleash the full improvisational power and dramatic tension of modern frontier models.

---

## ⚡ Core Capabilities

### 1. 🎭 Full SillyTavern Ecosystem Compatibility (Zero-Friction Asset Portability)
* **One-Click Lossless Character Card Import**: Native support for **V2 and V3 character cards** (both PNG format with embedded metadata and JSON). Restores avatars, full-body artwork, emotion sprite packs, greetings, and alternate example dialogues seamlessly.
* **Embedded Lorebook Parsing**: Character-embedded worldbooks, keywords, triggers, and persistent entries are automatically decoded and scheduled on demand.
* **Native 60+ Macro Engine**: Full native interpretation of official variables including `{{user}}`, `{{char}}`, `{{lastMessage}}`, and more. Run popular community cards straight out of the box with zero manual editing.
* **Author's Note**: Inject subtext, steering cues, or atmospheric anchors at any point in the conversation, with customizable insertion depth (At Depth) and system/user role targeting.
* **Multi-Character Group Chat**: Host multiple character cards in the same scene with flexible speaker turn scheduling (round-robin, targeted, or spontaneous).
* **Regex Scripting**: Bidirectional input/output regex pipelines to automatically strip reasoning chains, clean residual formatting, and refine dialogue layout.

---

### 2. 🧠 Modern LLM Architecture (Beyond Primitive Context Stuffing)
* **Frontier Ecosystem Direct Connection**: Tailored for modern frontier intelligence, offering native out-of-the-box support for major commercial APIs (Anthropic, Google, OpenAI, DeepSeek, Grok, and other leading frontier models), as well as third-party OpenAI-compatible proxies and local models.
* **Deep Prompt Caching Protection**: Strictly preserves static prefix context and eliminates per-turn dynamic branch drift, **slashing long-form RP token costs by 75% to 90%**.
* **On-Device Pure-Kotlin Vector Retrieval (RAG Engine)**: High-precision cosine similarity math (`cosineSimilarity`) and SQLite vector persistence running entirely on-device in Kotlin. Chunks are embedded once and cached locally forever, incurring zero repeated network calls during multi-turn exchanges.
* **Proprietary Tail Near-Context Injection (`MemoryVectorTailTransformer`)**: Large world lore and character backgrounds are no longer crammed into the System Prompt. Top-matching memory chunks are retrieved in milliseconds and dynamically injected right before the final user message, **safeguarding 100% of the Prompt Cache prefix while preventing model attention dispersion**.
* **Decoupled Dual-Engine Architecture (Front-Stage Roleplay, Background Memory Consolidation)**: The primary frontier model dedicates 100% of its compute to improvisational drama, while an independent lightweight model (the dedicated memory consolidation model) quietly consolidates long-term memories in the background without stealing turns or polluting flow.

---

### 3. 📱 Pure Android-Native Immersive Experience (No Webview Bloat)
* **Jetpack Compose Native Fluidity**: Instant startup, minimal battery drain, low heat, and buttery-smooth streaming typewriter rendering.
* **Full-Chain Audio & Voice (TTS / Voice Calls)**: Native speech synthesis and bidirectional voice conversation. Features **AST-Level Pure-Text Sanitization** (automatically bypassing code blocks, LaTeX formulas, and HTML tags during reading, ensuring speech never garbles punctuation or drops spoken dialogue).
* **Robust LaTeX & Rich Text Formatting**: Flawless rendering for LaTeX mathematical notation, Markdown code highlights, tables, and collapsible detail blocks, resolving historical formula truncation bugs when expressions contain `<` symbols.
* **Fluid Queue Management**: Instant generation interrupt; seamless follow-up message dispatch without queue freezing; backstack empty-crash immunity; and layout-safe favorite model drag sorting.

---

### 4. 🛡️ Zero Configuration, Zero Compromise
* **No Termux, No Tunnels**: Say goodbye to Termux environments, Node.js daemons, and keeping your desktop PC on. Install the APK and dive straight in.
* **No Sampler Slider Metaphysics**: Eradicates confusing legacy sampler sliders designed for primitive models. Pre-tuned with optimal baseline inference defaults for modern frontier reasoning.
* **No Wordy Jailbreak Bloat**: Eliminates contradictory ten-thousand-word templates, restoring raw, authentic, uncastrated character interactions.

---

## 🚀 Quick Start

1. **Install**: Grab the latest `zhongji-rp-*.apk` from [GitHub Releases](https://github.com/hufengjuexue/zhongji/releases), or update via **Komi Store**.
2. **Configure Models**: Open **Settings** → **Model Settings**, enter your API key, and select your primary chat and memory consolidation models.
3. **Import & Play**: Drop your favorite character card (PNG or JSON) into the main screen, tap, and begin your story!

---

## 🧬 Credits & Open-Source Heritage

ZhongJi RP evolves upon the shoulders of giants. We pay our highest respects to the open-source pioneers:
* **[RikkaHub](https://github.com/rikkahub/rikkahub)**: For establishing the modern, elegant Android Jetpack Compose architecture and high-concurrency streaming pipeline;
* **[Huadeng](https://github.com/MiaoWuNYA/rikkahub-sillytavern-android)**: For laying the critical foundation of lossless mobile SillyTavern protocol and macro parsing;
* **[LastChat](https://github.com/Cocolalilal/LastChat)**: For pioneering explorations in on-device RAG vector memory retrieval.

---

## 📄 License

This project is licensed under the [AGPL-3.0 License](LICENSE).
