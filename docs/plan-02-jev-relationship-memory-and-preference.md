# TypeFree「✦ Jev 军师」端侧关系记忆与个人语体偏好沉淀设计方案书（v2）

- **基线与目标版本**：TypeFree v3.5.2 -> v3.6.0（`versionCode = 30`）
- **代码落位约束**：纯算法与领域模型集中于 `com.typefree.ime.ime.jev.memory`（零 `android.*` 依赖、基于 `Gson` 序列化、注入 `JevClock` 便于将来无缝并入 `:core` 与 `:macos-companion`）；Android 宿主层承载 SQLite 后端、Keystore 盐值、IME 生命周期与 Compose 面板。
- **核心体验与隐私红线**：
  1. 关键路径零磁盘 I/O：全量记忆与别名索引常驻内存热缓存（`< 100KB`），唤起检索耗时 `< 0.2ms`；写入异步合批落盘（Write-Behind）。
  2. 键盘高度零跳变：严格兼容 `KeyboardMetrics.frameHeight`（含紧凑/标准/5行/横屏各档位）与 `360.dp` 窄屏顶栏，不新增额外高度。
  3. 端侧真实聊天原文与原始备注名零落盘、零上云；系统自动备份（`backup_rules.xml` / `data_extraction_rules.xml`）显式排除 `jev_memory_v2.db`。
  4. `PromptBuilder.JEV_COPILOT_SYSTEM_PROMPT` 保持 100% 编译期 `const val` 静态常量，保障云端 Prefix Cache 命中率；注入 User Prompt 的 `<relationship_context>` 控制在 `65 tokens` 以内。

---

## 一、总体架构与五层决策树融合设计

1. **端侧三表模型 + 纯内存热缓存 (`JevMemoryRepository`)**
   - `jev_session_memory`：以不可变随机 UUID 短串 `session_uid` 为主键，保存拓扑纠偏标记、私聊/群聊标签、经 `LocalPiiRedactor.redactRelationNote` 脱敏后的 40 字备注、可选首尾脱敏缩略名（仅本机设置页展示，严禁入 Prompt）、4 组策略轴 Simplex EMA 向量与 8 项语体编辑特征计数器。
   - `jev_anchor_alias`：`anchor_hash -> session_uid` 多对一别名映射表，解决用户修改微信备注名或群微调改名后的断链问题。
   - `jev_bucket_style_profile`：按 `"GLOBAL"` 与 `"PRIV::*"`/`"GRP::*"` 关系桶聚合跨会话个人语体与策略偏好，支撑新对话冷启动。

2. **高准确召回（抗噪 + 锁会话 + 窄条 OCR 补扫 + 别名无感续链 + 泛称隔离）**
   - `ConversationAnchorExtractor` 先按 `\n` 拆分多行节点并插值行坐标，隔离在线/活跃/部门副行，剥离尾部群人数 `(N)` 与听筒/静音图标，提取 `外部/@微信` 角标与稳定组织后缀（区分同名联系人）；
   - 将 Android 与 macOS 常见应用包名统一映射至跨端规范命名空间（如 `wechat`、`qq`、`feishu`、`wecom`、`dingtalk`、`telegram`、`xianyu`、`boss`、`xhs`）；
   - 命中 `momo`、`微信用户`、`客服`、`官方客服` 等易撞车泛称时标记 `isAmbiguousGeneric = true`，仅在当前输入框焦点周期内生效，禁止跨天写入持久库；
   - `EditorSessionAnchorPin` 在当前 `inputSessionToken + canonicalApp` 内钉死首次解析到的 `session_uid`，结合 90 秒 `VolatileWindowAnchorCache` 彻底免疫 `对方正在输入...` 覆盖顶栏；
   - 无障碍树漏检顶栏时，在 `TypeFreeAccessibilityService.captureChatViewportOutcomeOnDemand` 中顺带对原图顶部标题带（`0.036f..0.145f`）做轻量端侧 ML Kit OCR 补扫；
   - 内存中维护最多 12 个近期会话的「末 3 条气泡归一化文本 64-bit FNV-1a 短指纹」（锁屏/无痕/退出即清空，绝不落盘），用户改备注重新打开同一聊天时，若至少 2 条气泡指纹吻合即自动写入 `jev_anchor_alias` 续链。

3. **两阶段发送捕获 + 双向可逆保鲜（`JevDraftFeedbackMonitor` & `JevPreferenceLearner`）**
   - **阶段 1（`PREVIEW_IN_EDITOR`）**：用户在 `JevCopilotPanel` 点选策略卡片调用 `applyStrategyCard` 时立即挂载预监控，解决「不关军师面板直接点微信/飞书发送按钮」漏采的问题；
   - **阶段 2（`COMMITTED_EDITING`）**：关闭面板或切去打字面板触发 `commitAndFinishOnExit` 后进入 45 秒微调监控窗口，仅在非拼音/小鹤组合态且满足 250ms 节流时读取已有 `currentEditorText` 缓存，零额外同步 IPC；
   - **策略轴归因**：`JevResponseParser` 在排序置顶前为每个 `<opt>` 绑定原始 `rawSlotIndex`，并通过可选 `<route>` 区分 `PEER_TECH` / `TKI_GAME` / `GROUP_BYSTANDER` / `BOTTOM_LINE_LOCKED`；流式渲染阶段若用户已点选卡片，UI 锁定卡片顺序防手滑错位；
   - **双向逃逸防死锁**：未激活特征使用无偏步长 $\alpha_n = \max(0.20, 1/n)$ 归纳并封顶在 `0.90`；已激活特征在模型不再生成该符号后，若用户连续 2 次主动手动补回（如补句末句号、补表情、把 `你` 改回 `您`、扩写长句），按 $\alpha_{\text{off}} = 0.30, y = 0$ 衰减，跌破下限 `0.55` 自动解除激活。

4. **全高度自适应 UI 与诚实脱敏边界**
   - `JevCopilotPanel` 顶栏将关系标定合并至状态胶囊位（如 `⚡关系 ▾`、`💼客户·注 ▾`、`🛡2·关系 ▾`），保留 `ToggleJevContextInspector` 透视卡与 `⛶ 深读`；
   - 关系覆层 `JevRelationQuickOverlay` 渲染在 `Modifier.weight(1f)` 中心容器内并支持纵向滚动，适配紧凑（`227.dp`）、标准（`252.dp`）、5 行数字键（`294.dp`）及横屏（`190.dp`）全高度；
   - 语音录备注同时兼容双阶段 STT 与 `MULTIMODAL_DIRECT`（走 `DICTATE` 纯文本转写），备注栏明示「自动脱敏号码与地址，请勿填入真实姓名」，与 `LocalPiiRedactor` 实际能力严格对齐。

---

## 二、核心模块与源码落位

- `app/src/main/java/com/typefree/ime/ime/jev/memory/JevMemoryModels.kt`：核心枚举、统计数据类、`JevClock` 与紧凑 XML 序列化器
- `app/src/main/java/com/typefree/ime/ime/jev/memory/ConversationAnchorExtractor.kt`：跨端应用名归并、多行先拆后筛、泛称识别、加盐 HMAC 与会话锁定缓存
- `app/src/main/java/com/typefree/ime/ime/jev/memory/DraftDiffAnalyzer.kt`：滚动 DP `LCSRatio` 与双向语体编辑特征算子
- `app/src/main/java/com/typefree/ime/ime/jev/memory/JevPreferenceLearner.kt`：位次消偏单纯形 EMA、60天/120天时间收缩、双向施密特触发与 Layer 5 效用融合
- `app/src/main/java/com/typefree/ime/ime/jev/memory/RelationPragmaticMapper.kt`：轴组路由与四级规则冲突消解
- `app/src/main/java/com/typefree/ime/ime/jev/memory/JevMemoryStore.kt`：内存热表、Write-Behind 队列、气泡短指纹自动续链、分层 TTL/LRU 与加密备份负载
- `app/src/main/java/com/typefree/ime/ime/jev/memory/JevDraftFeedbackMonitor.kt`：两阶段发送捕获状态机
- `app/src/main/java/com/typefree/ime/ime/jev/memory/SqliteJevMemoryBackend.kt` & `AndroidKeystoreSaltProvider.kt`：Android SQLite 持久化与 Keystore 盐值
- `app/src/main/java/com/typefree/ime/ime/panels/JevRelationQuickOverlay.kt`：键盘内零跳变关系标定与备注覆层
- `app/src/main/java/com/typefree/ime/ui/screens/JevMemoryManagementDialog.kt`：宿主设置页关系记忆、已学语体规则开关与一键密钥轮转擦除管理
- `app/src/test/java/com/typefree/ime/v360/V360JevMemoryAndPreferenceV2Test.kt`：v2 全链路自动化验证测试集
