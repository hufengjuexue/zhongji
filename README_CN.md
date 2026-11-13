<div align="center">

<img src="docs/app-icon.png" width="96" alt="Kairos 图标"/>

# Kairos

### 极简纯净的安卓原生 AI 角色扮演客户端

**纯原生 Compose 手感 · 前缀缓存深度保护 · 尾部近因召回 · 时序记忆关系图谱**

[**简体中文**](README_CN.md) | [**English**](README.md)

[![Release](https://img.shields.io/github/v/release/hufengjuexue/kairos?label=Release&color=brightgreen)](https://github.com/hufengjuexue/kairos/releases)
[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](https://github.com/hufengjuexue/kairos/releases)
[![License](https://img.shields.io/badge/License-AGPL--3.0-blue)](LICENSE)

</div>

---

## 核心设计

**Kairos** 坚持「前端克制留白、大脑完全自主」的设计原则：
* **原汁原味 Compose 交互**：保留 RikkaHub 干净流畅的原生界面与排版手感，剔除一切网页套壳、花哨悬浮挂件与远古采样器滑块玄学。
* **前缀缓存死保（Prompt Caching First）**：头部系统提示词、常驻核心档案（`/profile.md`、`/preferences.md`）与记忆目录保持字节级稳定，消除跨天或更新文档导致的缓存击穿。
* **尾部近因召回（`MemoryVectorTailTransformer`）**：长篇 `topics/*.md` 档案与被上下文窗口截断的早期历史对话，按语义在请求发出前就近召回，仅在最后一条 `USER` 消息前作为合成记忆上下文动态注入。

---

## 核心能力

### 一、 记忆与上下文管线
* **解除“办公助手”反 RP 约束**：移除上游提示词中禁止扮演固定伴侣/禁止记录情感体验的条款，仅保留真实证件与密码隐私红线。
* **双源召回 + 词法兜底**：同时检索 Markdown 长期记忆库与超出窗口的早期对话原文；支持配置专属 Embedding 模型与扫描深度，在未配置或接口冷却期自动切换本地词法匹配兜底。
* **紧凑二进制向量缓存（`VEC1`）**：采用 Float32 二进制落盘存储，兼容旧版 `.vec` 文本缓存并内置容量上限惰性清理；同轮多步工具调用冻结当轮召回结果。
* **时序记忆关系图谱（Temporal Relation Graph）**：内置轻量级实体关系图数据库与 `recall_relations` / `graph_upsert_relations` 工具，以 `valid_from` / `valid_to` / `is_current` 记录角色羁绊、阵营、物品归属与誓约演变，旧关系自动留档进入历史时间线而非直接覆盖抹除。
* **压缩前打捞与可视化图谱页签**：上下文压缩检查点生成前自动提取即将折叠的轮次落库防丢失，并可在「助手记忆 → 关系图谱」页签通过交互式网络画布（拖拽缩放、类型筛选）与「当前 vs 历史时间线」面板直观审阅或手动增改关系。

### 二、 酒馆角色卡生态兼容
* **V2 / V3 PNG 与 JSON 一键直导**：完整导入角色头像、系统设定、开场白、`mes_example` 示例对话与 `post_history_instructions`。
* **内嵌世界书与正则自动解包**：自动提取角色卡内的 `character_book` 转为独立世界书并关联当前助手，同时自动解析 `extensions.regex_scripts`（兼容 `/pattern/flags` 语法）为消息正则规则。

### 三、 原生体验与独立身份
* **主流模型直连**：原生支持 Anthropic、Google、OpenAI、DeepSeek、Grok 及各类 OpenAI 兼容端点，并支持单独配置 Embedding 向量模型。
* **完全独立包名**：采用独立身份 `me.kairos.app` 与自适应图标体系（支持 Android Material You 单色主题取色）。

---

## 快速上手

1. **安装应用**：前往 [Releases](https://github.com/hufengjuexue/kairos/releases) 下载最新的 `kairos-*.apk`。
2. **配置接口**：进入「设置 → 模型设置」填入主力对话模型 API；如需向量精排召回，可在「通用设置 → 向量存储」中指定 Embedding 模型并调整扫描深度。
3. **导入角色**：直接导入 V2/V3 规格 PNG 或 JSON 角色卡即可开始对话。

---

## 致谢与协议

向以下开源基石致敬：
* **[RikkaHub](https://github.com/rikkahub/rikkahub)**：提供现代优雅的 Android Jetpack Compose 原生前端与底层通信框架；
* **[华灯 (Huadeng)](https://github.com/MiaoWuNYA/rikkahub-sillytavern-android)** 与 **[LastChat](https://github.com/Cocolalilal/LastChat)**：在移动端酒馆协议解析与端侧记忆召回上的先行探索。

本项目遵循 [AGPL-3.0 License](LICENSE) 开源协议。
