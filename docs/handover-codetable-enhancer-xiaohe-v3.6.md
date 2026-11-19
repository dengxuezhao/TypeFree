# TypeFree 小鹤音形与多方案码表增强架构交接文档（v3.6.0）

- **当前分支**：`feat/codetable-enhancer-xiaohe`（基于 `main` 提交 `2b2fa45`）
- **应用包名与版本**：`com.typefree.ime`，`xiaohe` flavor `versionCode = 32` / `versionName = "3.6.0"`（`defaultConfig.versionCode = 30`，同包名可直接覆盖升级）
- **交付安装包**：
  - 本地产物：`release/TypeFree-v3.6.0-小鹤音形专享版.apk`（`34,460,886` 字节，debugsigned）
  - Google Drive：`01_小鹤音形测试安装包 / TypeFree-v3.6.0-小鹤音形专享版.apk`（文件 ID：`1pD9-moZeEW9thI6vNrTrD-0HvptmR1SK`）
- **仓库合规边界**：
  - `.gitignore` 严格隔离小鹤专有词库及运行产物：`app/src/xiaohe/assets/rime/`、`release/`、`local_private/`、`exports/`、`*.table.bin`、`*.prism.bin`、`*.reverse.bin`、`*.brief.idx`。
  - 仓库内仅跟踪通用开源引擎代码、Apache-2.0 OpenCC 资源（`app/src/main/assets/rime/opencc/*.json` 与 `.ocd2`）及内置开源预设包 `app/src/main/assets/preset_schemas/xkjd6_preset.zip`（GPL-3.0）与 `app/src/main/assets/preset_schemas/wubi86_preset.zip`（LGPL-3.0）。
  - GitHub Actions 工作流 `.github/workflows/build-xiaohe-apk.yml` 在构建期按需注入私有词库，并强制校检 `git status --porcelain` 为空。

---

## 一、当前已落地架构与核心能力

### 1. 三层模块职责与源码映射

- **`:core`（纯 Kotlin JVM，零 Android / JNI 依赖）**
  - **方案画像与配置解析**：
    - `core/src/main/kotlin/com/typefree/core/ime/profile/SchemaProfile.kt`：定义四类输入原型 `SchemaArchetype`（`SENTENCE_PINYIN`、`FIXED_YINXING`、`FIXED_SHAPECODE`、`PUSH_TOPUP_CODE`）及行为开关（含 `englishPrefixVetoEnabled` 默认 `true`、`autoEncodePhrasesEnabled` 默认 `false`）。
    - `core/src/main/kotlin/com/typefree/core/ime/profile/SchemaInspector.kt`：依据 `.schema.yaml` 与词典二简多字占比、顶功正则特征自动识别方案原型并推导默认 `SchemaProfile`。
    - `core/src/main/kotlin/com/typefree/core/ime/profile/TypeFreeTomlParser.kt`：解析与序列化 `typefree.toml` 扩展声明。
  - **多方案码表按键协调器**：
    - `core/src/main/kotlin/com/typefree/core/ime/coordinator/TypeFreeKeyCoordinator.kt` & `CommitAction.kt`：统一调度双模态快符、原生直通口令、精确码与长码顺延、二简逆切分、英文前缀否决、阴影置顶/屏蔽、构词自动造词以及第 5 码顶字上屏。
  - **非码元双模态快符与括号居中**：
    - `core/src/main/kotlin/com/typefree/core/ime/overlay/OverlaySymbolController.kt` & `SymbolTrie.kt`：空闲状态按下 `;` 触发 1~2 码快符表（不占编码字母，支持 `quick_symbols.yaml` 编辑、成对符号 `PairedText` 光标居中、`;i` 撤回上屏、`;f` 重复上屏）；有编码候选时 `;` 保持「次选上屏」交互。
  - **前缀索引、空码顺延与逆切分**：
    - `core/src/main/kotlin/com/typefree/core/ime/enhancer/BriefPrefixIndex.kt`：持久化为 `.brief.idx`（`SNAPSHOT_VERSION = 2`），记录一二简首字存在性、`gt4`（`ok*`/`os*`）守卫前缀以及原始 `.dict.yaml` 行序下 2~7 码前缀的首条后续顺延词 `NaturalContinuation`；支持明文 `.dict.yaml` 扫描或对已编译 `.table.bin` 执行 702 槽探测重建。
    - `core/src/main/kotlin/com/typefree/core/ime/enhancer/ReverseSplitEngine.kt`：4 码精确匹配为空且通过一二简存在性与非 `ok/os` 守卫检查时，拆分为「2 码首字 + 2 码后段」（如 `hfkn -> 很可能 / 困难`），以虚词候选形式呈现，不污染底层 Rime 用户词库。
  - **英文前缀否决、原地置顶/屏蔽与构词造词**：
    - `core/src/main/kotlin/com/typefree/core/ime/enhancer/EnglishWordTrie.kt`：内置长度 >= 5 的常用小写英文词 26 叉 Trie，当 4 码小写输入无精确中文匹配、且命中更长英文词前缀时（如 `gith`、`goog`），在 4~14 码区间抑制 `ReverseSplitEngine` 与第 4/5 码中文误顶屏，展示英文补全候选；当 4 码存在精确中文词（如 `pyth -> 跑一趟`）时仍优先保留中文。
    - `core/src/main/kotlin/com/typefree/core/ime/enhancer/ShadowDictManager.kt`：读写 `<schemaId>.shadow.yaml`，维护按编码原地置顶 `pinnedByCode` 与全局隐藏 `blockedWords`，零改动主 `.dict.yaml`。
    - `core/src/main/kotlin/com/typefree/core/ime/enhancer/CodetableAutoEncoder.kt`：开关默认关闭；开启后根据连续单字上屏序列按 `AaAbBaBb`（2 字）、`AaBaCaCb`（3 字）、`AaBaCaZa`（4 字）派生 4 码词条写入 `<schemaId>_user.txt` 并实时召回。
  - **原生直通口令**：
    - `core/src/main/kotlin/com/typefree/core/ime/enhancer/DirectCommandEngine.kt`：支持 `=` 四则/幂算式、`orq`/`date` 及 `orq±N`/`date±N`（`-3650..+3650` 天偏移，含公历与农历）、`ouj`/`time`、`oxq`、`ofi` 剪贴板汉字编码反查（已并入启用的 `ext/*.dict.yaml`）、`odz` 单字模式开关、`ovy` 中英标点、`ojf` 简繁、`oai` 联动 Jev 军师、`zz` 及 `zza`~`zzf` 分组符号；其中 `oqm` 全码字开关已严格限定在 `schemaId == "flypy"` 生效。
  - **开放方案包导入与子词库/用户词管理**：
    - `core/src/main/kotlin/com/typefree/core/ime/importer/SchemaPackageImporter.kt`：支持明文 `.schema.yaml`/`.dict.yaml` 与桌面版预编译 `build/*.table.bin` `.zip` 导入；对 `flypy` 保留 Kotlin 原生增强管线并裁剪 Lua，对非小鹤方案（如 `xkjd6`）完整保留 `rime.lua`、`lua/**` 与 `- lua_*` 组件。
    - `core/src/main/kotlin/com/typefree/core/ime/importer/SubDictToggleManager.kt`：管理 `flypy_full` 及 `ext/*.dict.yaml`/`.tsv` 扩展词库挂载、开关补丁与 `ofi` 跨分库反查索引构建。
    - `core/src/main/kotlin/com/typefree/core/ime/importer/CustomUserPhraseFileEditor.kt`：UTF-8 编辑 `<schemaId>_top.txt` 与 `<schemaId>_user.txt`（保留 `#@/db_name` 等 tabledb 文件头，防重复与非法空白）。

- **`:rime`（C++17 JNI 与 `librime 1.17.0`）**
  - `rime/src/main/cpp/rime_jni.cpp` & `rime/src/main/java/com/typefree/rime/RimeBridge.kt`：
    - 静态编译并注册 `librime-lua`（`rime_require_module_lua()`）、`octagram` 与 `opencc`。
    - 候选与上屏文本以 UTF-8 `ByteArray` 跨 JNI 传递，兼容 CJK 扩展区 4 字节生僻字。
    - `nativeDeploy` 显式检查 `select_schema` 返回值不再静默吞错；同步提供 702 槽批探测能力。

- **`:app`（Android IME、引擎编排与 UI）**
  - `app/src/main/java/com/typefree/ime/ime/engine/RimeDataManager.kt`：负责内置资源部署（含 `default.yaml` 注册与 `opencc/` 拷贝）、旧版 v1 `.brief.idx` 自动升级至 v2、预设/第三方 `.zip` 导入、扩展库导入与 `ofi` 反查合并、快符持久化与重置。
  - `app/src/main/java/com/typefree/ime/ime/engine/CodetableEnhancedInputEngine.kt` & `TypeFreeInputMethodService.kt`：衔接 `RimeDataManager`、`TypeFreeKeyCoordinator`、`ShadowDictManager` 与 `CodetableAutoEncoder`，处理口令执行、`odz`/工具栏单字开关及候选长按置顶/屏蔽事件；并在升级 `versionCode = 32` 时一次性平滑迁移老用户默认方案。
  - `app/src/main/java/com/typefree/ime/ime/engine/PresetSchemaCatalog.kt`：声明内置开源方案 `xkjd6`（星空键道 6）与 `wubi86`（五笔 86）。
  - `app/src/main/java/com/typefree/ime/ime/candidates/CandidateStrips.kt`：候选长按弹出菜单（`置顶首选` / `取消置顶` / `屏蔽该词`）与切分/提示标签渲染。
  - `app/src/main/java/com/typefree/ime/ime/KeyboardToolPanel.kt`：工具面板顶部条新增 `单字` 状态切换胶囊；`BuiltInQwertyKeyboard.kt` 在小鹤空闲态 `，` 键长按/上滑及符号层提供 `;` 触发，保留原 6 键底排与 `252.dp` 高度。
  - `app/src/main/java/com/typefree/ime/ui/screens/DictionaryScreen.kt` & `CodetableAdvancedManagementCard.kt`：在码表模式下折叠原拼音词库下载与旧 TSV 兼容卡片，并在「码表进阶管理」折叠卡片内提供 6 个管理面板（预设方案一键安装、`_top`/`_user` 词条增删、`;` 快符编辑与恢复默认、子词库开关与 `.dict.yaml`/`.tsv` 导入、英文防误上屏与自动造词开关、`.shadow.yaml` 规则查看与清空）。

---

## 二、与清风输入法（WindInput）在四码领域的对比及现存差距

### 1. 同构能力对照

| 能力维度 | 清风输入法（WindInput） | TypeFree 当前实现 | 现存差距评估 |
| :--- | :--- | :--- | :--- |
| 运行平台与生态定位 | 桌面三端成熟；自研 `codetable` 引擎（不走 Rime `speller/algebra`） | Android 触屏优先，保留 `librime 1.17.0` + `librime-lua` + Rime 方案生态 | 桌面伴侣尚在规划；Android 触屏与 Jev AI 联动为差异化强项 |
| 码表原序与四层分档 | `base_sort = "natural"` + `base_order` 分档 | `sort: original`、`lockNaturalOrder = true`、`initial_quality` 四层权重 | 基本齐平 |
| 空码按原序提示后续首码 | 内置原序补全顺延 | `BriefPrefixIndex` v2 记录 2~7 码 `NaturalContinuation` 并置顶首位 | 首选已对齐原序；展开栏第 2~N 项补全仍按 Rime `abc` 序 |
| 英文前缀防第 4 码顶屏 | 内置英文词表拦截满码上屏 | `EnglishWordTrie`（>= 5 字母常用词）拦截无精确中文匹配的 4 码前缀 | 已可用；暂不支持导入外部大型英文词库或自定义冲突策略 |
| 候选原地置顶 / 屏蔽 | `shadow.dict.yaml` 候选右键菜单 + 设置管理 | `ShadowDictManager`（`<schemaId>.shadow.yaml`）+ 候选长按菜单 + UI 面板 | 原地置顶/屏蔽体验基本齐平 |
| 一二简保护下的半衰期调频 | 锁一二简首选不动 + 四码重码按近期频次衰减调频 | 仅支持静态锁死原序 + 手动置顶/屏蔽 | **未实现（明确差距）** |
| 按构词规则自动造词 | `[[encoder.rules]]` 可配置多长度取码规则 | `CodetableAutoEncoder` 写 `<schemaId>_user.txt`（默认关闭） | 固定内置 `AaAbBaBb`/`AaBaCaCb`/`AaBaCaZa`，未开放自定义公式配置 |
| 多级分组符号 (`zz`) | 候选区一级选类后原地翻入二级符号列表 | `zz` 显示 `zza`~`zzf` 引导与常用符号，继续输入 `a`~`f` 出分组符号 | 支持分类输出；点击一级 `zza 常用符号` 提示不能自动下钻 |
| `$CC` 按键序列宏与内嵌加词弹窗 | 支持发 `End`/`Left` 键序与输入态 `$CC` 弹窗加词 | `PairedText` 实现括号居中；App 词库页编辑 `_top`/`_user` 与 `;` 快符 | **未实现任意按键宏与键盘内嵌编辑弹窗** |
| 扩展与分类子词库管理 | 桌面端配置 + 设置开关 + 反查扫扩展库 | `SubDictToggleManager` + `.dict.yaml`/`.tsv` 导入 + `ofi` 合并 `ext/` | 基本齐平 |
| 二进制专有码表导入 | 私有 `.wdat` 挂接包 | Rime 预编译 `build/*.table.bin` 直通 + 702 槽探测 | 格式体系不同（Rime 开源轨 vs 清风 `.wdat`） |

### 2. 相比清风仍然存在差距的具体点

1. **缺少“一二简保护下的半衰期动态调频”**：清风允许在锁死一二简首选位的前提下，仅让四码重码按指数半衰期动态升降并随时间衰减回码表原序（删频可恢复）。TypeFree 当前采用纯静态码表排位 + `.shadow.yaml` 显式手动置顶/屏蔽，适合固定编码党，但尚未提供自动动态调频选项。
2. **`$CC` 任意按键序列发送与输入态快捷弹窗未实现**：清风的直通指令可发送 `Home`/`End`/`Left` 等虚拟键序列，或直接在打字时弹窗增删个人词。TypeFree 针对括号居中通过 `PairedText` 单独实现，通用按键宏暂缺，手工加词/改快符则放在 App「词库 → 码表进阶管理」界面中完成。
3. **`zz` 分组符号的一级菜单点击不自动下钻**：敲 `zz` 时展示 `zza 常用符号` 至 `zzf 标点/括号` 提示行和常用符号，用户需继续在键盘敲出 `a`~`f` 进入子组；若直接点击 `zza 常用符号` 提示胶囊，因该候选为只读提示（`CommitAction.PlainText("")`），不会把编码缓冲区切换为 `zza`。
4. **英文前缀词库扩展性有限**：`EnglishWordTrie` 内嵌约 600+ 常见英文单词且仅拦截 4 码无精确中文匹配场景；对冷门英文专有名词（或与低频四码汉字重码但用户仍倾向输英文的词）目前不支持挂接外置英文词典。
5. **构词公式暂不可配**：`CodetableAutoEncoder` 仅内置了双码单字的 2/3/4 字取码规则，尚无法像清风 `[[encoder.rules]]` 那样由用户按五笔、虎码等方案自定义各字码长取位公式。
6. **不兼容清风私有 `.wdat` 格式**：仅兼容 Rime `.yaml`、`.tsv`/`.txt` 及官方 Rime 编译产物 `.table.bin`。

---

## 三、与「散步的鹤」建议（清风反馈 + 微信聊天截图）对照及差距

### 1. 对照总表

| 「散步的鹤」诉求点 | 来源依据 | TypeFree 当前状态 | 现状说明与差距 |
| :--- | :--- | :--- | :--- |
| 非码元双模态 1~2 码快符 + 成对括号居中 | 社区反馈 + 截图二 | **已实现** | `;` 空闲发快符（含 `quick_symbols.yaml` 编辑 UI），有码保持次选 |
| 空码二简逆切分 (`hfkn` / `sma`) | 社区反馈 + 截图二 | **已实现** | 仅拆 4 码空码且守卫一二简与 `ok`/`os`，不污染用户词库 |
| 超四码引导 (`ok`/`os`) 防误切 + 局部补全 | 社区反馈 | **已实现** | `gt4` 守卫 + `ok_mode` 修复 + 主表关闭补全 |
| 码表原始行序锁死 + 四层固定权重 | 社区反馈 + 截图二 | **已实现** | 主词库与 Rime 保持原序；静态手感完整保留 |
| 官方 `.table.bin` 免明文挂接与四字节扩展汉字 | 社区反馈 | **已实现** | 702 槽探测直通 + JNI UTF-8 `ByteArray` + `Utf8Safety` |
| **空码按原始码表行序提示后续第 1 个编码** | **微信截图一** | **已实现第 1 候选** | `BriefPrefixIndex` v2 `NaturalContinuation` 已将 `okhg -> 丁〔~uu〕`、`qfx -> 清风徐来〔~l〕` 置顶于首选；但**展开栏后续第 2~N 项仍按 Rime `abc` 序** |
| **手机底排压缩为 5 键 + 次选键 `,` 放大至与功能键同宽** | **微信截图一** | **未实现（本轮按要求暂缓）** | 见下方差距 1 |
| 分类词库图形化开关 + `ofi` 反查覆盖扩展库 | 社区反馈 | **已实现** | `CodetableAdvancedManagementCard` + `SubDictToggleManager` 完成 |
| 个人词库编辑 / 日期偏移 / `zz` 分组 / 单字模式 / 隐藏冗余拼音 | 社区反馈 + 截图二 | **核心功能已实现，全局界面藏冗未完成** | `_top`/`_user`、`orq±N`、`zz..zzf`、`odz`/工具栏 `单字`、词库页折叠拼音卡片均已落地；见下方差距 2 |

### 2. 相比「散步的鹤」建议仍存在的具体差距

1. **【截图一核心 · 暂缓待排期】底排 5 键精简与 `,` 次选键加宽**：
   - 在与散步的鹤聊天截图一中，他明确指出「手机空格行最好能压缩成五个键，次选键很重要，应该放大到和功能键同宽的地步」。
   - 根据需求确认，本轮刻意未改动 `BuiltInQwertyKeyboard.kt`（第 669~825 行）：目前底排仍保持 6 键总权重 `10.00`——`123 (1.35)` | `中/英 (1.00)` | `，次选 (1.00)` | `空格 (3.95)` | `。三选 (1.00)` | `回车 (1.70)`，因此 `,` 次选键宽度仍只有 `1.00`，窄于功能键 `123` (`1.35`) 与 `回车` (`1.70`)。
   - 若后续立项补全，可在设置中增加「四码专属 5 键底排」布局切换：将独立 `中/英` 键收起至空格手势或工具栏顶条，把 `,` 次选权重提升至 `1.35~1.45`。
2. **【截图二延伸】纯码表场景下，输入法其它界面仍残留拼音专属控件**：
   - 目前仅在 `DictionaryScreen.kt` 对「雾凇词库下载/旧 TSV 兼容工具」做了码表模式默认折叠。
   - 未收起的位置包括：`KeyboardToolPanel.kt` 顶部栏常驻的拼音专属 `轻声模式` 开关，以及 `AccountSettingsScreen.kt` 输入方案切换区平铺的 `全拼`、`小鹤双拼` 与整句 AI 选项，整体尚未实现类似清风 `custom.toml` `hide` 的全链路极简纯码表界面模式。
3. **空码补全展开后第 2 位及后续词条仍遵循 `abc` 序**：
   - `BriefPrefixIndex` v2 目前仅对每个 2~7 码前缀记录码表原序的**第 1 条**后续顺延词并劫持到第 1 候选位（满足「空码时显示后续第一个编码」）；如果用户展开候选面板查看同一前缀下第 2 条往后的更多补全，依然由 `librime` 底层按后缀字母序吐出。
4. **外置复杂 Lua 方案兼容边界**：
   - `SchemaPackageImporter` 已放行非小鹤方案的 `rime.lua`/`lua/**`，内置精简 `xkjd6_preset.zip`（含 `topup_processor.lua`）也在真 `librime-lua` 下实测通过，但部分强依赖 PC 端特定接口或外部滤镜插件的社区魔改 `Rime_JD` 发行包，导入前仍需逐包核查。

---

## 四、测试与构建基线（v3.6.0 `versionCode = 32`）

在最后一次代码与测试提交 `029a9ac` 后实测结果如下：

- `:core:test`：**25 项通过**（0 失败 / 0 跳过，覆盖 `CodetableEnhancerQaSuiteTest`、`CodetableV361EnhancementsTest`、`JiandaoAndSubDictImporterTest`）。
- `:app:testStandardDebugUnitTest`：**264 项通过**（0 失败 / 0 跳过）。
- `:app:testXiaoheDebugUnitTest`：**264 项通过**（0 失败 / 0 跳过，含接入宿主机 `rime/build/host-macos/librime_jni.dylib` 真引擎与 `librime-lua` 的 `V361NativeLibrimeAcceptanceTest`、`V361CodetableIntegrationTest`、`XiaoheCodetableAcceptanceTest`）。
- 打包与安装核验：
  - `./gradlew assembleXiaoheRelease assembleStandardDebug --no-daemon`
  - `aapt2 dump badging release/TypeFree-v3.6.0-小鹤音形专享版.apk`：`package: name='com.typefree.ime' versionCode='32' versionName='3.6.0'`。
  - `emulator-5554` 执行 `adb install -r` 在 `versionCode = 31` 上覆盖升级成功。

---

## 五、下一阶段排期建议

1. **P1 · 四码 5 键底排开关与宽次选键**：为 `BuiltInQwertyKeyboard.kt` 提供可选的 5 键四码底排，将 `,` 次选键权重从 `1.00` 扩宽至 `1.35~1.45`（与 `123` 同宽），并将 `中/英` 切换保留在空格手势或工具栏。
2. **P2 · 纯码表极简隐藏模式**：在 `KeyboardToolPanel.kt` 与 `AccountSettingsScreen.kt` 按激活原型隐藏 `轻声模式`、拼音双拼入口等无关配置。
3. **P3 · `zz` 一级分类点击下钻与半衰期调频可选项**：允许点击 `zz` 一级分类芯片直接切入 `zza`~`zzf`；按需评估是否新增一二简保护下的四码重码半衰期开关与输入态 `$CC` 加词弹窗。
