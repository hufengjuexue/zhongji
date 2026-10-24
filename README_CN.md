<div align="center">

<img src="docs/app-icon.png" width="96" alt="终极 RP 图标"/>

# 终极 RP · ZhongJi RP

### 为现代前沿大模型重铸的安卓原生沉浸式 RP 客户端

**开箱即入戏 · 酒馆全生态无损兼容 · 独创端侧向量尾部近因注入 · 提示词前缀缓存深度保护**

[**简体中文**](README_CN.md) | [**English**](README.md)

[![Release](https://img.shields.io/github/v/release/hufengjuexue/zhongji?label=Release&color=brightgreen)](https://github.com/hufengjuexue/zhongji/releases)
[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](https://github.com/hufengjuexue/zhongji/releases)
[![License](https://img.shields.io/badge/License-AGPL--3.0-blue)](LICENSE)

</div>

---

## 🌟 为什么需要「终极 RP」？

**过去的 AI RP（角色扮演）生态，长期被旧时代的“自虐式妥协工程”所困扰：**
* **旧时代酒馆（SillyTavern）** 诞生于 7B / 13B 弱智本地小模型时代。为了强行逼出逻辑，堆砌了上百个滑动条、采样器玄学、万字破限八股文，甚至逼着移动端用户去折腾 Termux、配置 Node.js 或开着电脑搞内网穿透——**这些都是旧时代的妥协产物，而不是真正的先进体验。**
* **普通聊天 App** 虽轻便，却毫无角色沉浸感。长文本失忆、无法读取角色卡与世界书、上下文动辄分叉爆窗，完全无法支撑深度 RP 体验。

**现代大模型已经拥有极高维的语义感知与情感张力，它们不再需要万字咒语去“教大模型做人”。**

> 💡 **「终极 RP」的初心：去芜存菁，终结繁琐。**  
> 删掉旧时代所有的残疾补丁与反人类门槛，哪怕第一天接触 AI 的小白也能一键开聊；保留并深度重构真正强大好用的核心架构，将现代大模型最纯粹的即兴推演力与情感心流彻底释放。

---

## ⚡ 核心功能矩阵 (Core Capabilities)

### 一、 🎭 酒馆全生态无损兼容（零门槛继承最强 RP 资产）
* **全规格角色卡一键直导**：原生支持主流 **V2 / V3 规格角色卡**（PNG 图片格式与 JSON 格式）。角色头像、全身立绘、差分表情包（Emotion Sprites）、开场白及多轮示例对话完整还原。
* **内嵌世界书 (Lorebook) 智能解包**：角色卡内嵌的数十乃至上百条世界观条目、触发关键词与常驻设定自动解包，按现代逻辑按需召回。
* **官方 60+ 宏引擎原生支持**：完整支持 `{{user}}`、`{{char}}`、`{{lastMessage}}` 等全部官方宏变量，社区热门角色卡拿来直接开跑，零手动魔改。
* **作者附注 (Author's Note)**：对话中随时随地插入剧情暗线或情绪定调，支持自定义插入深度（At Depth）与系统角色定位，毫厘之间掌控剧情脉络。
* **多角色同场群聊 (Group Chat)**：支持多张角色卡同场互动，自由设定发言轮询、点名或抢答机制。
* **输入 / 输出正则脚本 (Regex)**：内置输入输出双向正则过滤器，自动剥离思维链杂质，定制角色台词与心理描写的排版风格。

---

### 二、 🧠 现代大模型新型架构（颠覆旧时代的无脑堆砌）
* **全生态主流大模型直通**：专为现代前沿大模型设计，原生支持各大主流商业 API（涵盖 Anthropic, Google, OpenAI, DeepSeek, Grok 等全系前沿智能体），以及任意第三方兼容中转站与本地私有化模型。
* **提示词前缀缓存 (Prompt Caching) 深度锁定**：严格固化前缀上下文，彻底消除每轮对话中的动态分叉点，**长篇 RP 费用直接节省 75% ~ 90%**，越聊越省钱。
* **端侧纯本地向量语义检索 (RAG Engine)**：基于纯 Kotlin 打造的高精度端侧余弦相似度（`cosineSimilarity`）数学算法与本地 SQLite 向量持久化库，设定切片一次编码后终身驻留本地，单轮多步对话零重复网络开销。
* **独创端侧尾部近因注入 (`MemoryVectorTailTransformer`)**：大型背景小传不往头部 System 堆砌。根据最新发言端侧毫秒级匹配记忆切片，在最后一条 User 消息前夕动态近因注入。**100% 保护 Prompt Cache 前缀，大模型注意力永不涣散**。
* **前后双模解耦架构（前台演戏，后台静默萃取）**：前台主力大模型纵情演戏推演；后台可指定独立的轻量模型（记忆整理专属模型）静默提炼长时记忆与滚动压缩，长对话不失忆、不爆窗、不抢跑。

---

### 三、 📱 纯原生移动端沉浸体验（告别网页套壳的发热与卡顿）
* **Jetpack Compose 纯原生流式响应**：秒开秒回，低功耗、低发热，带来如丝顺滑的打字机流式输出手感。
* **全链路真实语音 (TTS / 语音通话)**：原生集成语音合成与双向语音对聊；独创 **语法级 AST 纯文本清洗**（朗读时自动跳过代码、公式与 HTML 标签，绝不误念乱码，绝不吃掉尖括号与正文）。
* **LaTeX 与富文本稳健排版**：全面支持 LaTeX 数学公式渲染、Markdown 语法、代码高亮与折叠块，彻底修复公式包含 `<` 符号被截断损坏的历史痛点。
* **现代消息队列与交互防崩**：打断即停、停止生成后立即追加发消息不卡死队列；连按返回键空栈防闪退；收藏模型本地拖拽防抽搐；滑动删除平滑结算。

---

### 四、 🛡️ 终结自虐工程，小白零门槛
* **免配置、免部署**：告别 Termux、告别 Node.js、告别电脑开机挂穿透，安装 APK 打开即入戏。
* **告别滑块玄学**：彻底剔除旧时代针对远古弱智模型的几十个混乱采样器滑块，出厂预置针对现代智能体的最佳推理基线。
* **告别破限咒文**：无需满世界寻找相互打架的万字破限八股与防代皮念经，释放大模型最本真、有张力的剧情碰撞。

---

## 🚀 极速上手 (Quick Start)

1. **安装应用**：前往 [Releases](https://github.com/hufengjuexue/zhongji/releases) 下载最新的 `zhongji-rp-*.apk`，或通过手机端 **Komi Store** 一键安装。
2. **配置模型**：进入「设置」→「模型设置」，填入你的 API Key 并选择心仪的主力模型与记忆模型。
3. **导入角色**：在主界面直接选入你珍藏的酒馆角色卡（PNG 或 JSON），点击即可立刻开聊！

---

## 🧬 开源血脉与先驱致敬 (Credits & Heritage)

「终极 RP」站在开源巨人的肩膀上进化而来，向以下开源先驱致以崇高敬意：
* **[RikkaHub](https://github.com/rikkahub/rikkahub)**：提供了极为优雅现代的原生 Android Jetpack Compose 架构与高并发通信基座；
* **[华灯 (Huadeng)](https://github.com/MiaoWuNYA/rikkahub-sillytavern-android)**：奠定了移动端对酒馆协议无损解析与宏引擎兼容的坚实基石；
* **[LastChat](https://github.com/Cocolalilal/LastChat)**：启发了移动端 RAG 向量记忆检索的先锋思考。

---

## 📄 开源许可 (License)

本项目遵循 [AGPL-3.0 License](LICENSE) 开源协议。
