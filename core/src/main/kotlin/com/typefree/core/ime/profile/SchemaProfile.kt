package com.typefree.core.ime.profile

/**
 * Input method schema archetype classification for TypeFree v3.6 multi-schema codetable engine.
 */
enum class SchemaArchetype {
    /** 四码定长音形（如：小鹤音形 flypy，二简多字词首选占比 >= 25%，默认开启空码二简逆切分） */
    FIXED_YINXING,

    /** 编码空间分离 + 变长集合顶功（如：星空键道 6 xkjd6、声笔系列、虎码顶功，交由 librime 原生顶屏） */
    PUSH_TOPUP_CODE,

    /** 纯字形定长码表（如：五笔 86、虎码纯形版、郑码，二简多字词占比 < 10%，关闭逆切分） */
    FIXED_SHAPECODE,

    /** 整句双拼/全拼（如：小鹤双拼、雾凇拼音、白霜拼音，启用整句解码与 Auto-Learn Engine v2.0） */
    SENTENCE_PINYIN
}

/**
 * Declarative capability profile driving `:core` enhancement interceptors without modifying
 * upstream `librime` C++ core or introducing `librime-lua`.
 */
data class SchemaProfile(
    val schemaId: String,
    val displayName: String,
    val archetype: SchemaArchetype,
    val maxCodeLength: Int = 4,                  // 0 表示不限定长（整句拼音或自由变长方案）
    val lockNaturalOrder: Boolean = true,        // 锁死码表原始行序（禁用 user_dict 调频）
    val allowAutoLearn: Boolean = false,         // 是否接入 TypeFree Auto-Learn Engine v2.0
    val overlayGuideKey: Char? = ';',            // 空闲态进入快符子表的非码元引导键
    val overlayShowAllOnEnter: Boolean = true,   // 进入快符时立即铺开首页符号供点选
    val semicolonSelectsSecond: Boolean = true,  // 组码有候选时分号选次选
    val reverseSplitEnabled: Boolean = false,    // 空码 2+(1~2) 二简逆切分（仅默认对 FIXED_YINXING 开启）
    val reverseSplitSuffixOnly: Boolean = true,  // 次选仅显示后段词（如「1 很可能  2 困难」）
    val fifthKeyTopCommit: Boolean = true,       // 定长方案第 5 码自动顶首选上屏（集合顶功方案交由 librime）
    val autoCommitUniqueFull: Boolean = true,    // 满码唯一且无更长后继时自动上屏
    val directCommandsEnabled: Boolean = true,   // 启用原生日期/农历/计算器/剪贴板反查直通车
    val reverseLookupDict: String? = null,       // 绑定的单字反查词库名（如 flypydz）
    val gt4GuardPrefixes: Set<String> = emptySet(), // 超四码引导前缀（如小鹤 ok, os）
    val spellerAlphabet: String = "abcdefghijklmnopqrstuvwxyz'",
    val englishPrefixVetoEnabled: Boolean = true,
    val autoEncodePhrasesEnabled: Boolean = false
) {
    val overlayEnabled: Boolean
        get() = overlayGuideKey != null && overlayGuideKey !in spellerAlphabet

    fun isCodeAlphabetChar(ch: Char): Boolean = ch in spellerAlphabet

    companion object {
        fun defaultXiaoheFlypy(): SchemaProfile = SchemaProfile(
            schemaId = "flypy",
            displayName = "小鹤音形",
            archetype = SchemaArchetype.FIXED_YINXING,
            maxCodeLength = 4,
            lockNaturalOrder = true,
            allowAutoLearn = false,
            overlayGuideKey = ';',
            overlayShowAllOnEnter = true,
            semicolonSelectsSecond = true,
            reverseSplitEnabled = true,
            reverseSplitSuffixOnly = true,
            fifthKeyTopCommit = true,
            autoCommitUniqueFull = true,
            directCommandsEnabled = true,
            reverseLookupDict = "flypydz",
            gt4GuardPrefixes = setOf("ok", "os"),
            spellerAlphabet = "abcdefghijklmnopqrstuvwxyz'"
        )

        fun defaultSentencePinyin(
            schemaId: String = "rime_ice",
            displayName: String = "雾凇拼音"
        ): SchemaProfile = SchemaProfile(
            schemaId = schemaId,
            displayName = displayName,
            archetype = SchemaArchetype.SENTENCE_PINYIN,
            maxCodeLength = 0,
            lockNaturalOrder = false,
            allowAutoLearn = true,
            overlayGuideKey = ';',
            overlayShowAllOnEnter = true,
            semicolonSelectsSecond = true,
            reverseSplitEnabled = false,
            reverseSplitSuffixOnly = false,
            fifthKeyTopCommit = false,
            autoCommitUniqueFull = false,
            directCommandsEnabled = true,
            reverseLookupDict = null,
            gt4GuardPrefixes = emptySet(),
            spellerAlphabet = "abcdefghijklmnopqrstuvwxyz'"
        )
    }
}
