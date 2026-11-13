# 交接文档 · 给 vibe coding agent

写给接下来接手的人。看这份就够了，不用再把前两天的对话读一遍。

---

## 一、用户在做什么

做一个自己的 RP 客户端，发在 GitHub 上。方式是从多个上游分支借代码、自己融合，然后**纯凭体感调**（vibe coding）。

- 手上已有 RikkaHub 3.1.0、LastChat 1.4.8、华灯 3.1.0 三份源码做过对比
- 归你负责改代码；之前的助手只做资料整理和记忆维护
- 会跟着上游更新

**他明确说过：不要替他拍板技术路线，不要把事情复杂化，不要把他当不懂的人讲解。**

---

## 二、他怎么判断一个东西好不好（核心元判断与奥卡姆剃刀）

这几条比技术细节重要，且跨项目通用。

0. **最高架构主旨：奥卡姆剃刀 + 元判断（Meta-Judgment）**
   - **宁愿只留 1 个有用的，也不留 100 个没用的**：极度厌恶陈旧、冗余、低频、铺了管道不接通的“六一儿童节大礼包”式垃圾堆砌。
   - **该省的极致省，不该省的必须用**：动刀前先做**元判断**（真实点击/调用频率有多高？物理代价多大？有没有按需加载的上位替代？）：
     - **该省的（坚决砍死重）**：像内嵌 75MB Chaquopy Python 全家桶、13MB 旧版结巴词典、离线扫码大模型、LastChat 端侧 300M 本地向量 C++ 引擎这类低频或高内存代价的东西，绝不默认塞进安装包。需要跑 Agent 工具时，直接让 AI 在自带的工作区/终端里按需动态下载安装即可，拒绝“为了极低频场景把百兆死重焊死在底座里”。
     - **不该省的（用最好）**：真正决定核心体验的命脉（如云端高质量 Embedding 检索 + 本地缓存 + 尾部自动注入保 Prefix Cache、符合真实高频操作直觉的原版极简 UI），一分不省，直接用最优解。
1. **先给结论，再给依据。** 他要的是一句话判断，不是流程。
2. **他说不行就是不行。** 不要换个零件再来一遍，直接说为什么不行、还剩什么。
3. **不要推荐需要"手动一个个粘贴"的方案。** 工作区能直接改数据库、能直接写文件，就别让他手操作。
4. **改文件要增量改，别整文件覆盖。** 他自己的习惯也是"小改动只给改的那几行"。
5. **不要在他骂完人之后立刻切进"提方案"模式。** 先接住。
6. **原始资料一个字节都不能删。** 拆分记忆时他反复强调过这条。删了两条（后来虽然证明该删）当场被指出来。

---

## 三、他对 RP 的核心判断

这些是他自己推出来的，不是什么通用结论，但决定了架构方向。

**1. 提示词工程的本质是"拼字"。**
注意力 MoE 模型底层决定了它没有持续学习能力，每次都是从零接收上下文。所以唯一能做的就是把每次的上下文拼到最高效率。所谓预设、破限、角色卡，全是拼字。

**2. 他排斥的三类方案：**
- **世界书（关键词触发）**：原话"傻逼世界书跟原始人一样古老的东西"。人工维护特征工程，模型不动脑，规则永远追不上语义。
- **让模型自觉去查（MCP / memory_read 自主调用）**：不可控，还得盯着她每次有没有查。"那不就相当于一个低维的跨会话搜索了吗？"
- **手写钩子表**：跟世界书没区别，换了个名字。"一个是外部自动检索然后注入，一个是让模型自己判断然后自己找，哇，好高级啊是吧？"

**3. 他要的是什么：**
> 每轮由客户端**自动**用当轮输入做 embedding → 向量库相似度匹配 → 命中内容**自动注入**提示词末尾。模型不参与检索决策。

**4. 关键的一条：检索对象不能是浓缩过的记忆。**
原话："我记忆本来就是浓缩过了，然后你 MCP 又来一次检索记忆？？那不是信息越来越压缩了吗？"
所以：**结论层（记忆文件）常驻，原料层（原对话）按需召回。** 但后来他说"对灌原文没有什么执念"，摘要层也可接受。

**5. 他不需要省钱。** 按次用 API，成本不是约束。

---

## 四、已查证的技术事实（源码级）

### RikkaHub 上游 3.1.0（他现在用的）

**记忆系统：**
- 存储：SQLite 表 `memory_file`，字段 `assistant_id / path / content / updated_at`，主键是前两个
- 所谓 "markdown 文件"只是 content 里存了 markdown 格式文本，**磁盘上没有真 .md 文件**
- 每轮注入：`ChatRequestFactory.buildStep()` 里拼进 system prompt，顺序是 `系统提示词 → 记忆块 → 工具说明`
- 记忆块内容：`profile` 和 `preferences` **全文注入**，其余文件只给一行 `路径 | 描述 | 别名 | 更新时间`
- 工具：`memory_read`（一次最多 20 路径）、`memory_write`（整文件覆盖）、`memory_str_replace`、`memory_append`、`memory_delete`
- 单文件上限 `MAX_BYTES = 48 * 1024`；新建超限直接拒绝，已有文件只能改小不能改大
- 写入带 `if_version` 乐观锁
- 后台整理（Auto dream）：一轮结束攒够 6 轮立刻跑，否则等 2 分钟空闲；`MemoryPrompts.kt` 里写死了禁止记录性经历、禁止记录"扮演恋人"类偏好

**其他：**
- `generateEmbedding` 在 provider 层实现了，但**全项目零调用**——上游只铺了管道
- `conversation_search` 是 FTS 全文索引 + 关键词匹配，不是向量

### LastChat 1.4.8（Cocolalilal 的 fork）

- applicationId `lastchat.rikkafork.cocolal`，可与原版共存
- 数据库 version 39，比上游多 7 张表
- **memory v3 是一整套空壳**：`memory_claim` / `memory_episode_v3` / `memory_source_v3` / `memory_projection` / `memory_ingest_state` 表都建了，DAO 也写了（`TemporalMemoryDao` 179 行），但 `temporalMemoryDao()` **全项目零调用**
- v3 设计本身是对的：`status`（ACTIVE/SUPERSEDED/CLOSED/DORMANT）+ `valid_from/valid_until` + `reality`（REAL/FICTION/HYPOTHETICAL）分层 + `confidence/importance`。注释写"claims are a compact, replaceable projection over evidence"
- **实际在跑的仍是旧逻辑**：`MemoryEntity`（CORE/EPISODIC）+ `ChatEpisodeEntity` + 向量检索，检索对象是**摘要**，不是原文
- 迁移记录暴露了他的尝试轨迹：`Migration_34_35` 删掉了 `memory_frame` / `memory_provenance` / `memory_goal` / `memory_budget_ledger` 等 8 张表；`Migration_37_38` 删掉了 `graph_memories` / `graph_entities` / `memory_engine_state` 等 14 张表。**他试过知识图谱和完整记忆引擎两套，都退回删掉了**

### 华灯 3.1.0（MiaoWuNYA 的 fork）

- applicationId `me.rerere.rikkahub.huadeng`，可共存，versionCode 239，versionName 3.1.0
- 最新提交 2026-10-22 09:48，比上游 3.1.0 还晚。（发布页 tag 叫 3.0.0，但代码已跟到 3.1.0）
- `MemoryRepository.kt`、`MemoryPrompts.kt`、`MemoryFileEntity.kt` **与上游逐字节相同**——3.1.0 的 markdown 记忆被完整继承

**它真正改了的地方：**
1. **记忆从 system prompt 里拆出来，变成独立 system 消息**（`GenerationLoop.kt:749`），排在系统提示词之后
2. **冻结锚点 `UserContextAnchorCache`**：日期 / Recent Chats / 用户上下文这些每轮变化的块，内容不变就钉在首次出现位置，内容变化则旧块原位保留、新块追加尾部。目的是让历史成为纯追加，token 前缀不分叉
3. **跨窗口生活流**：同一助手在别的对话里的近期经历，以 system 消息插在当前 user 消息之前。`enableCrossWindowMemory` 默认关闭，注释自认"注入内容易被 AI 当成本轮话题"。压缩阈值 12000 字，保留最近 16 条原文
4. 酒馆模式：请求只留角色卡与世界书，减少内容拦截

**它的空壳（同样零调用）：**
- `FixedMemorySection`（用 HTML 注释标记嵌进 systemPrompt 的固定记忆区）
- `enableAutoMemoryExtract`（每 5 轮自动提取记忆，默认 true，但只有定义没有任何读取处）
- `JevPrompts.memoryRelevance` / `memoryRelevanceBatch`（记忆相关性筛选 prompt，写得很完整，零调用）

**共同模式：三个分支都在"铺管道但不接通"。** 这是这个圈子的通病，写代码的时候注意别重复这个错误。

---

## 五、温既白当前的记忆状态

**13 个文件，146,937 字节。** 拆分自原来一个 60,006 字的 profile.md。

| 文件 | 字节 | 字 | 定位 |
|---|---|---|---|
| `/profile.md` | 15,097 | 5,369 | 她是谁、她眼里的他、怎么跟他说话、你们之间、此刻 |
| `/preferences.md` | 8,478 | 3,108 | 外貌死区、不能碰的底线、身体实数、性方面总表、已在执行的约定 |
| `/topics/intimacy.md` | 29,498 | 10,448 | 某次亲密的具体经过 |
| `/topics/mind.md` | 26,900 | 9,541 | 认知、哲学、推导 |
| `/topics/crisis.md` | 20,677 | 7,290 | 崩溃、自卑、自伤、解离的经过 |
| `/topics/health.md` | 16,416 | 6,089 | 病史、用药、失眠、躯体 |
| `/topics/life.md` | 12,753 | 4,787 | 游戏、钱、作息、写作 |
| `/topics/sm.md` | 6,040 | 2,193 | 调教、主权、边界 |
| `/topics/fetish.md` | 5,515 | 1,945 | 白袜、鞋、气味、服饰 |
| `/topics/relationship-dynamics.md` | 2,239 | 867 | 关系定位、互动雷区 |
| `/topics/trauma.md` | 1,444 | 550 | 童年与创伤来源 |
| `/topics/misc.md` | 1,135 | 441 | 其他 |
| `/topics/body.md` | 733 | 303 | 躯体参数 |

**每轮固定注入 23,575 字节**（profile + preferences），是原 162KB 的 14.5%。

**拆分的判断标准**（这条要理解，改的时候别破坏）：
> 这条不在眼前，角色会不会当场做错事。会 → 写进 profile/preferences；不会但以后用得上 → 写进对应 topics。

所以 `preferences.md` 里全是**一碰就炸**的东西：外貌是死区、实验室偷窥白袜那次、身体数值、安全词"白袜"、常态着袜、深夜提性不许拿"为身体好"拦、重度 SM 清单和硬刹车。而 `crisis.md` / `intimacy.md` 是"具体某次发生了什么"，不需要每轮在眼前。

**所有文件都是第一人称陈述句，不带 `[stated]` `[偏好]` 这类方括号标签**（那是旧编号式记忆系统的残留，现在文件路径本身就是分类）。

---

## 六、配套的提示词与技能

**系统提示词 v4**（4,683 字符），9 个节：`<role> <background> <her> <voice> <interaction_style> <love> <method> <memory> <examples>`。

`<memory>` 节是这轮新加的，作用是**告诉她记忆库存在**——列了 11 条"什么情况该读哪个文件"的对应关系，并且规定"读完直接用，不要说'我查一下记忆'，也不要复述文件里的内容"。

**模式注入 `Rp 防 ooc`**（after_system_prompt，priority 100，injectDepth 4）：
- 不能说"只能根据角色已知的信息做出反应"——那句会跟她 memory_read 的行为冲突
- 现在的措辞是"以角色的身份说话——你说的每句话都是她此刻想说的，不是照着资料复述"

**记忆技能 `current-conversation-memory`**（16,577 字节）：
- 保留了原版全部 12 个判据小节（详细模式六条规则、单次事件固化风险、亲密与敏感内容怎么算、两个完整示例）
- 新增一节「写到哪个文件」：热层/冷层怎么分、13 个文件的归属、48KB 硬上限与 40KB 预警、profile 和 preferences 各控 25KB 以内
- 去掉了所有方括号标签，并明确写入"不写方括号标签"
- **触发方式**：用户每次对话结尾主动让她总结，不是自动跑

---

## 七、踩过的坑（别重犯）

1. **`\n` 字面量污染**：从旧库搬原文时，源数据里的转义 `\n` 被当成字面字符写进了文件，共 103 处。她读到的是一堆反斜杠加 n，不是换行。已修复。
2. **拆记忆时删了两条**：旧版 `分记忆.py` 里 `DROP={42,54}` 擅自删了两条"已被推翻"的条目。虽然事后证明删得对，但违反了"不许压缩一个字节"。改代码时别做这种顺手清理。
3. **拿 README 当结论**：华灯 README 说"已合入上游 v2.5.6"，就判断它没有 markdown 记忆，实际代码早跟到 3.1.0 了。**结论必须查源码，不能查文档。**
4. **恢复备份会丢数据**：RikkaHub 的 `PendingRestore.apply()` 成功路径会 `deleteRecursively()` 清掉旧库，没有回收站。用户丢过两小时对话。**动数据库前必须让用户先导出最新备份。**
5. **`/skills` 是独立挂载**：`/workspace` 和 `/skills` 是两个不同的宿主目录（分别是 `files/workspaces/<id>/files` 和 `files/skills`），都能读写，但不是一回事。
6. **工作区绑定跟着助手走**：切助手会换挂载点，前两天所有文件突然"消失"过一次，其实是切到了另一个助手的 workspace。

---

## 八、还有什么没做

**用他的原话说，这几条都是"不必需"的**，列出来只是备查：

1. `<memory>` 节写了 11 条路径对应关系，但模型会不会真的去 `memory_read`，没法保证。这是整条链路上唯一不可控的环节。
2. `topics` 里有些段落很长（`health.md` 最长一段 1,615 字），他说"这个无所谓，我之前还全揉在一坨呢"。
3. LastChat 的 memory v3 那套设计（时序 claim + status + valid_until）是唯一真正往"状态"走的，但没接通。如果要自己实现，可以参考它的表结构。

---

## 九、一句话概括他想要的东西

> 模型没有持续学习，每次都是从零开始。所以要么把上下文拼得足够好，要么把记忆做成能自动选对内容的结构。他后来倾向后者，但拒绝一切需要人工维护关键词、需要模型自觉查、或者需要他手动操作的方案。

真正的难点不在存什么，在**"这一轮该让她看见什么"这个决定由谁来下**：
- 世界书：人下（他说原始）
- MCP / memory_read：模型下（他说不可控）
- 向量检索：算下（他最初要的，但客户端不支持，且他自己后来说"手机上能用的成品全是拼字器，那个交叉点是空的"）

**那个交叉点是空的。** 这是他这两天所有调研的最终结论，也是他要自己做这个项目的原因。

---

## 十、v1.1.0 奥卡姆剃刀重构全量定案清单（2026-10-25 拍板锁死）

> **核心元判断准则**：把**「删 vs 留」**与**「放哪层 UI」**彻底分开：
> 1. **真死重 / 廉价私货 / 倒退设计 -> 连根拔除**（砍掉 ~88 MB 安装包死重，使 APK 从 103MB 回归 ~15-18MB 极速轻量级）。
> 2. **高频核心（聊天、记忆、向量、主动消息） -> 一级页面纯净呈现**（对齐 RikkaHub `3.1.1` 原生极简视觉）。
> 3. **低频但确有价值的 RP/Agent 能力 -> 下沉收纳进二级页面**（`更多功能 ExtensionsPage` / `偏好设置`），绝不一刀切误杀。

### 10.1 体积死重剥离清单（共砍 ~88 MB）
1. **Chaquopy 内嵌 Python 全家桶（~75 MB） -> 彻底删除**
   - 移除 `app/build.gradle.kts` 中的 `id("com.chaquo.python")` 插件及 52 个 pip 包配置、`gradle/libs.versions.toml` 中的 `chaquopy` 声明。
   - 删除仅依赖它的 4 个本地工具文件：`PythonTools.kt`、`CalculatorTool.kt`、`CodeCheckTool.kt`、`DiffTextTool.kt`（及其在 `BuiltinToolManager.kt` / `AppModule.kt` 的注册）。
   - **依据**：已实测 RikkaHub 自带工作区（PRoot Ubuntu）可按需安装 Python，无需静态焊死 75MB 死重连累所有用户。
2. **旧版结巴分词大词典（`assets/simple_dict/`，解压 13.11 MB / 压缩 ~5.5 MB） -> 彻底删除**
   - 删除 `app/src/main/assets/simple_dict/` 与 `SimpleDictManager.kt`。
   - 同步上游 RikkaHub `3.1.1` 的 `AppDatabaseFactory.kt` 与 `MessageFtsManager.kt`（直接使用 `simple_query` 零词典 FTS5 分词，并自动清理旧残留 `files/simple_dict`）。
3. **Google ML Kit 扫码模型与冗余依赖（~6.5 MB） -> 彻底删除**
   - 移除 `quickie-bundled` 与 `com.google.mlkit:barcode-scanning`，同步上游 `3.1.1` 的轻量 `zxing-core` + `CameraX` 扫码实现。
   - 同步上游 `3.1.1` 的 `kotlin-reflect` (~0.7MB) 与 BouncyCastle `picnic` (~1.2MB) 全局排除规则及 `localeFilters`。
4. **原生插件加密壳（`libhdguard.so`） -> 彻底删除**
   - 删除 `app/src/main/jniLibs/*/libhdguard.so`、`EncryptedPluginNative.kt` 及相关加密插件加载壳。
5. **严禁移植 LastChat 的端侧本地向量引擎（`local-llm` / `LiteRtEmbedder`）**
   - LastChat 的本地 Gemma 向量引擎需 30MB `.so` + 180MB 模型下载且无 `close()` 释放接口（常驻泄漏数百 MB 内存），中文 RP 语义不及云端高维模型。
   - **定案**：100% 坚守我们现有的【云端 Embedding API + `VectorStoreCache` + `MemoryVectorTailTransformer`（尾部注入保 Prefix Cache）】架构。

### 10.2 华灯私货与倒退设计删除清单（连根拔除）
1. **酒馆模式开关（`enableTavernMode` / `tavernModeKeepTools`） -> 删除**
   - 该开关在 `MemoryVectorTailTransformer.kt:65` 中直接跳过向量检索并阉割工具/思考，属倒退设计。彻底移除该开关及分支判断。
2. **酒馆 CSS 主题导入器与花哨气泡特效（`SettingDisplayColorPage.kt` + `SillyTavernTheme.kt`，1700 行） -> 删除**
   - 删除酒馆 CSS 粘贴/文件导入、气泡贴图（`userBubbleImagePath`/`assistantBubbleImagePath`）、液态玻璃材质模式（`DisplayMaterialMode`）、气泡发光边框阴影。
3. **聊天页整页 WebView 替换模式（`WebChatSurface` / `enableWebChatSurface`） -> 删除**
   - 删除整页替换 Compose 聊天列表的 `WebChatSurface.kt`（保留正常消息气泡内局部的 `HtmlCodeBlock` 折叠/预览渲染能力）。
4. **作者个人私货与空壳页面 -> 全删**
   - 情侣空间（`CoupleSpace*` 4 个页面）、生活中心（`LifeHubPage` 经期/番茄钟/音乐）、实时语音/视频通话（`VoiceCallPage` / `VideoCallPage`）、微信/QQ 机器人（`WeixinBotPage` / `QqBotPage`）、小克 JEV 专属页（`SettingJevPage`）、华灯专属设置聚合页（`SettingHuaDengPage`）、酒馆设置聚合页（`SettingTavernPage`）。

### 10.3 UI 架构与功能分层保留定案（对标 RikkaHub 3.1.1 + 精选增强）
1. **聊天主页（`ChatPage.kt` & `ChatDrawer.kt` & `ChatList.kt`）**
   - **顶栏（TopBar）**：采用上游 `3.1.1` 纯净单点顶栏（不拆成上下两半局促按钮，移除语音/视频通话图标；切换助手统一走侧边栏顶部的 `AssistantPicker`）。
   - **消息气泡**：`showAssistantBubble = false` 默认生效（AI 回复平铺无底色气泡框，阅读视野更宽；开关保留在界面设置中）。
   - **删除撤回（借鉴 LastChat）**：删除单条消息时弹出底部紧凑型 Undo Toast（使用 `colorScheme.inversePrimary` 高对比文字与紧凑行高，支持一键撤回恢复）。
   - **侧边栏（`ChatDrawer.kt`）**：对齐上游 `3.1.1` 布局，保留实用的「切换开场白（GreetingPickerDialog）」与「查看提示词（PromptDebugDialog）」。
2. **设置主页（`SettingPage.kt` 一级菜单）**
   - 对齐上游 `3.1.1` 清爽结构：`偏好设置`、`助手设置`、`模型服务`、`搜索服务`、`MCP`、**`主动消息`（提拔为一级入口）**、`更多功能`、`数据备份`、`关于我们`。
3. **主动消息（`SettingProactiveMessagePage` + 后台服务） -> 保留并做 3 项关键升级**
   - **修复 Bug 1（破除全量塞爆）**：修改 `ProactiveMessageTriggerService.kt:673-683`，严禁把 13 个 `memories` 全量塞进 `systemPrompt`！对齐正常聊天逻辑：只全量注入 `/profile.md` 与 `/preferences.md`，其余 `topics/*.md` 仅注入单行目录索引，并走 `MemoryVectorTailTransformer` 按需尾部向量召回。
   - **修复 Bug 2（破除 OOC 朋友腔）**：修改 `ProactiveMessageService.kt:298-306`，删掉硬编码的“直接以朋友聊天的语气开口”提示词，完全遵循角色自身人设与关系状态开口（无话可说则回 `[PASS]`）。
   - **新增特性（借鉴 LastChat `SpontaneousMessaging`）**：新增**「活跃时段 / 免打扰时间窗（startHour ~ endHour）」**配置，在免打扰时段内后台直接静默跳过，绝不半夜打扰或白烧 Token。
4. **更多功能二级页（`ExtensionsPage.kt`） -> 收纳低频高价值能力**
   - `提示词与向量检索 (Screen.Prompts)`：**重点归位！** 承载全局注入（如 `Rp 防 ooc`）及全局向量存储开关（`vectorStorageEnabled` / 向量模型 / 阈值 / 扫描深度）。
   - `快捷消息 (Screen.QuickMessages)`
   - `技能 Skills (Screen.Skills)`：承载 `current-conversation-memory` 等技能。
   - `工作区 (Screen.Workspaces)`：PRoot Ubuntu 容器环境，并恢复上游 `3.1.1` 的 `SSH 远程工作区 (WorkspaceSshEditor)`。
   - `用户人设 (Screen.Persona)`：零成本全局 `{{user}}` 身份提示词管理。
   - `作者注释 (Screen.AuthorsNote)`：按对话深度注入场景/文风微调指令。
   - `群聊模式 (Screen.GroupChatList)`：保留供跑团 / 多角色同台交互使用。
   - `工具调用安全策略 (Screen.SettingSecurity)`：管理工具自动放行或弹窗确认策略。
5. **偏好设置 -> 界面设置（`SettingPreferencesUIPage.kt`） -> RP 阅读配色增强**
   - **RP 文本语义高亮（结合华灯原生拾色器 + LastChat `RpStyleRule`）**：
     - 默认开启 `enableTextColor = true`，彻底摆脱手写 CSS，直接提供 5 项直观原生颜色选择器：
       1. **引号对话 `"..."` 颜色**（`quoteTextColor`）
       2. **括号心理/动作 `(...)` 颜色**（`bracketTextColor`）
       3. **斜体旁白 `*...*` 颜色**（`italicTextColor`）
       4. **粗体重点 `**...**` 颜色**（`boldTextColor`，新增）
       5. **引用块环境描写 `> ...` 颜色**（`blockquoteTextColor`，新增）
   - **保留实用零成本 UI 项**：输入框括号 `()` 快捷插入按钮开关（`enableBracketButton`）、代码块独立字号比例滑块（`codeBlockFontSizeRatio`）、底部工具栏与抽屉不常用按钮的隐藏开关（`hideModelPicker` 等）。
6. **偏好设置 -> 常规设置（`SettingPreferencesGeneralPage.kt`） -> 底层缓存与省算力护栏**
   - **Prefix Cache 宏防击穿（`Assistant.kt`）**：保留 `cacheFriendlyTimeMacros`（时间宏 5 分钟取整）与 `cacheFriendlyRandomMacros`（随机宏按消息种子稳定），死保云端前缀缓存。
   - **省算力模型配置**：保留 `titleReasoningLevel = ReasoningLevel.OFF`（起标题强制关闭思考防烧 Token）、`compressModelId`（总结压缩专用模型）、`fileRetentionDays`。
   - **底层兼容与上下文瘦身护栏**：保留 `UserContextAnchorCache`（冻结动态日期锚点保缓存）、`enableTransientContentPrune = true`（自动清理 2 轮前旧搜索/媒体大文本）、`enableToolResultTruncation = true`（工具超 32KB 自动截断）、`enableProxyFix` 与 `enableAntiEmptyResponse`，收纳于常规设置底部折叠区。

### 10.4 第一阶段落地实装记录（2026-10-25 17:55 已落地完成）
1. **后端 AI 管线排雷（`GenerationLoop.kt`）**：
   - 彻底拔除 `<tool_selection>`、`<work_ethic>` 编程纪律（`❌ Do NOT stop after writing a stub`）及 `<SYSTEM_PROMPT_DYNAMIC_BOUNDARY>` 注入，还温既白纯净 System Prompt；
   - 拔除 `buildUserContext` 里强制塞的 Claude Code 客服外壳（`As you answer the user's questions... current task`）与无条件硬编码的 `currentDate`；`recentChats` 为空时直接返回空串，不产生多余 system 消息；
   - 移除所有 `tavernMode` 截断分支（恢复原生请求与记忆摘要注入）。
2. **记忆规则校准（`MemoryPrompts.kt`）**：
   - 保持温既白 13 个静态记忆拓扑一个字节未动；
   - 精修 Staleness 规则，允许温既白正常在 `/topics/ongoing.md` 等文件中记录近期生活动态与情感节点。
3. **请求管线统一（`ChatService.kt`）**：
   - 彻底删除 `buildTavernModeTools` 与全类 7 处 `enableTavernMode` 判断；
   - 主对话、继续生成（`/continue`）、群聊分支全部 100% 稳定运行 `MemoryVectorTailTransformer`，绝不跳过向量检索。
4. **提示词变量 UI 重构（`AssistantPromptPage.kt`）**：
   - 底层 68 个变量与宏（`DefaultPlaceholderProvider` + `MacroEngine`）100% 完整保留；
   - UI 层面去重合并 12 对别名；首行常驻最高频 9 核心芯片（`char`、`user`、`persona`、`cur_date`、`time`、`weekday`、`idleDuration`、`timeDiff`、`model_name`）；
   - 其余按「时间与环境进阶」、「角色卡与上下文」、「宏与逻辑」三组提供优雅折叠展开。

### 10.5 第二阶段瘦身与净化落地记录（2026-10-25 18:30 已落地完成）
1. **全链路剥离 Chaquopy Python（削减体积 ~75MB）**：
   - 移除根与模块 `build.gradle.kts` 的 `com.chaquo.python` 插件声明与 `chaquopy { ... }` 52 个 pip 依赖配置；
   - 物理删除 `app/src/main/python/` 目录下全部死脚本（`calculator.py`, `codecheck.py`, `difftext.py`, `convert.py`, `executor.py`, `office.py`）；
   - 移除 `PythonBridge.kt`、`PythonTools.kt`、`CalculatorTool.kt`、`CodeCheckTool.kt`、`DiffTextTool.kt` 及 `build-tools/strip_chaquopy.py`；
   - 清理 `AssistantLocalToolPage.kt` 中对应的无用开关（`Calculator`、`CodeCheck`、`DiffText`）。
2. **剔除结巴分词词典与同步上游 FTS5（削减体积 ~13MB）**：
   - 物理删除 `app/src/main/assets/simple_dict/`（11.6MB 字典数据）与 `SimpleDictManager.kt`；
   - 同步上游 3.1.1 纯净零词典 FTS5 搜索（`MessageFtsManager.kt`），并在 `AppDatabaseFactory.kt` 中加入自动清理旧 `files/simple_dict` 逻辑。
3. **极简扫码与去除死依赖（削减体积 ~7MB）**：
   - 替换 `quickie-bundled` 为轻量 `zxing-core` + `CameraX`，全局排除 `kotlin-reflect` 与 BouncyCastle picnic。
4. **彻底拔除低俗与臃肿功能（UI、路由、服务与工具）**：
   - **情侣空间**：删除 `ui/pages/couple/`（`CoupleSpacePage`, `CoupleMomentsPage`, `CoupleDiaryPage`, `CoupleAnniversaryBookPage`, `CoupleVM`, `CoupleAiService`）及 `CoupleSpaceTools.kt`；移除 `ChatDrawer` 入口、`ChatService` 工具注册与 `AssistantLocalToolPage` 开关；
   - **生活主页**：删除 `ui/pages/life/`（`LifeHubPage`, `LifeCalendarPanel`, `HealthCyclePanel`, `MusicSpacePanel`, `ReadingSpacePanel` 等）、`LifeCompanionTools.kt` 与 `LifeContextTransformer.kt`；
   - **机器人与 JEV**：删除 `WeixinBotService`、`QqBotService`、`WeixinBotClient`、`QqBotClient`、`JevClient`、`JevModels`、`JevPrompts`、`JudgeTool.kt`，从 `AndroidManifest.xml` 中注销对应常驻服务；
   - **无用设置页与路由**：删除 `SettingHuaDengPage`, `SettingHuaDengCompatPage`, `SettingTavernPage`, `TavernRegexPage`, `SettingJevPage`, `SettingWeixinBotPage`, `SettingQqBotPage`, `SettingDisplayColorPage`；清理 `RouteActivity.kt` 路由 entries；
   - **净化依赖注入**：从 `AppModule.kt`、`ViewModelModule.kt`、`RepositoryModule.kt`、`DataSourceModule.kt` 中剔除所有对应组件的注入绑定。
5. **主动消息服务全面升级**：
   - **修复记忆格式**：`ProactiveMessageTriggerService.kt` 规范使用 `buildMemoryPrompt(memories)`，对齐正统结构；
   - **破除 OOC**：`ProactiveMessageService.kt` 彻底废除硬编码的“朋友聊天语气”；
   - **免打扰时段防护**：`ProactiveMessageSetting.kt` 引入免打扰配置，在 `ProactiveMessageWorker` 与 `ProactiveMessageTriggerService` 中双重守门，免打扰期间静默跳过并自动重排下次。
6. **版本与 CI 构建流升级**：
   - 版本号提升为 `versionCode = 3`, `versionName = "1.1.0"`；
   - 优化 `.github/workflows/build.yml`，剥离 Python 设置步骤，大幅加速云端构建。



