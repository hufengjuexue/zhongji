# Kairos 工程交接文档（v1.3.1 · `E:\kairos`）

写给接下来接手的开发者与 AI Agent。看这份即可掌握当前仓库形态、双轨分支纪律与核心架构，无需翻查早年“华灯 / 终极 RP”过渡记录。

---

## 一、 仓库、身份与发版现状

- **项目名称**：**Kairos**（由原 `zhongji` / `huadeng` 演进独立）
- **GitHub 仓库**：`hufengjuexue/kairos`（`myfork` 指向该仓库；`upstream` 指向原版 `RikkaHub`）
- **本地工作目录**：`E:\kairos`
- **Android 包名**：`me.kairos.app`（与原版 RikkaHub 彻底隔离共存，使用独立自适应 Lain 图标）
- **当前版本**：`v1.3.1`（`versionCode = 198`，紧接 `v1.3.0` / `197`；`update.json` 与 `UpdateChecker.kt` 均已对齐 `hufengjuexue/kairos`）
- **编译与测试环境**：Windows 宿主机与 WSL2 无本地 JDK / Android SDK，统一由 `.github/workflows/build.yml` 在 GitHub Actions（Java 21 + `setup-android-native`）执行 **全模块 JVM 单元测试 + `assembleGithubRelease`** 并自动发布 GitHub Release（`arm64-v8a` / `universal` APK）。

---

## 二、 双轨分支纪律（防作者信息与历史污染）

| 分支名 | 定位 | 操作规则 |
| :--- | :--- | :--- |
| `upstream-sync` | 日常开发与追上游分支 | 完整保留 `RikkaHub 3.2.0+` 上游合并历史与日常增量提交；追上游时先在这里 `git merge upstream/master`（或 `upstream/main`）并解决冲突。 |
| `main` | 干净的单作者发版分支 | 只保留 `hufengjuexue` 单作者压缩提交；合入代码时切到 `main`，执行 `git merge --squash upstream-sync && git commit`，确保树与 `upstream-sync` 零 diff 且不引入上游数十位提交者签名污染，再推 `myfork main` 触发发版 CI。 |

---

## 三、 核心元判断与架构取舍（奥卡姆剃刀）

- **该省的极致省**：坚决不静态内嵌百兆级 Python (Chaquopy)、离线结巴词典、MLKit 本地模型或 300MB 端侧向量 C++ 引擎；Agent 执行代码按需走 `Workspace`（PRoot Ubuntu / SSH）按需拉取。
- **不该省的用最好**：死保云端高质量 Embedding + 尾部近因注入（保云端 Prefix Cache 不分叉）+ 决策模型二阶段精排 + Room v34 时序记忆图谱。
- **不代替人工加无用私货**：UI 保持原生 Material 3 Expressive 简洁风格，拒绝花哨气泡贴图与冗余空壳页面。

---

## 四、 Kairos 核心能力矩阵（基于 RikkaHub 3.2.0+）

1. **尾部向量召回与决策精排（`MemoryVectorTailTransformer`）**
   - **头部常驻**：`profile.md` 与 `preferences.md` 常驻顶部系统上下文，不参与分片漂移。
   - **一阶段粗召回（Stage 1）**：`topics/*.md` 与超出 `limitContext` 的早先原始对话按语义切块，优先走云端 Embedding 余弦相似度（`VEC1` 二进制 Float32 本地持久化缓存 `VectorStoreCache`）；无嵌入模型或接口异常时自动走本地中英文词法相关性兜底。
   - **二阶段决策精排（Stage 2）**：`ModelType.DECISION`（Google `gemini-decision-1`，走 `POST /v1beta/decisions` 单前向批量评估；留空时若已启用提供商含 Decision 模型则自动激活）或用户显式选择的轻量 `CHAT` 模型（超短 Listwise 索引重排，强制关闭 Thinking 防消耗），对粗召回并集候选池（上限 24 条）做上下文裁决，过滤误召回后保留 Top-6 并按 `(path, order)` 排序注入最后一条 User 消息之前；同轮多步工具调用期间冻结召回结果（`frozenTurnRecall`），死保前缀缓存。
   - **故障无感降级**：Embedding 或 Decision 接口异常均带 60 秒冷却窗口（TTL），自动回退一阶段 / 词法兜底，绝不阻塞主对话。

2. **时序记忆关系图谱（Room v33 → v34）**
   - **实体与迁移**：`MemoryGraphEntity.kt`、`Migration_33_34.kt`、`MemoryGraphRepository.kt`，使用 `valid_from` / `valid_to` / `is_current` 记录实体关系演变，旧关系转入历史时间线而不覆盖丢失。
   - **AI 工具与压缩前打捞**：`MemoryGraphTools.kt`（`recall_relations` / `graph_upsert_relations`）接入对话与后台做梦整理（`MemoryConsolidator`）；`ChatService.kt` 在生成压缩检查点摘要前先调用 `extractBeforeCompression(...)` 抢救待折叠轮次。
   - **可视化与联动**：助手详情记忆页新增「关系图谱」Tab（`MemoryGraphTab.kt`，Canvas 交互拓扑 + 筛选 + 当前/历史时间线与手工增改）；聊天消息中的图谱工具调用胶囊可直达对应助手图谱。

3. **Provider 兼容性与 RP 体验护栏**
   - **首轮 Assistant 先手兼容**：`ChatCompletionsAPI` 自动为无前置 user 的首条 Assistant 消息补轻量合成 user 占位。
   - **中部 SYSTEM 角色透传与合并**：`GoogleProvider` / `ClaudeProvider` / `InteractionsAPI` 将中部 `SYSTEM` 消息（尾注记忆/世界书）原地转为 user 轮并自动合并连续 user 轮（`@JvmOverloads` 兼容原有反射单测）。
   - **RP 提示词工程**：`PersonaInjectionTransformer`、`PromptInjectionTransformer`（全球/模式注入/世界书）、`PlaceholderTransformer`（宏与日期）、`PromptDebugCache`（侧边栏「查看提示词」前缀分叉诊断）、`enableAntiEmptyResponse`（`systemPromptInChat`）。

4. **全模块单测覆盖**
   - 涵盖 `:ai`（含 `GoogleDecisionApiTest`、各 Provider 协议与流回放）、`:app`（含 `SettingsFieldsTest`、`MemoryGraphTest`、`MemoryVectorTailTransformerTest`、`CharaCardImportTest`、`MemoryPromptTest` 等）、`:search`、`:ui`、`:workspace`。
