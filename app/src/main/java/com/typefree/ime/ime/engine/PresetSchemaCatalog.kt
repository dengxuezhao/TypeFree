package com.typefree.ime.ime.engine

object PresetSchemaCatalog {
    data class PresetSchemaInfo(
        val id: String,
        val displayName: String,
        val assetPath: String,
        val license: String,
        val archetypeLabel: String,
        val description: String
    )

    val BUILT_IN_PRESETS: List<PresetSchemaInfo> = listOf(
        PresetSchemaInfo(
            id = "xkjd6",
            displayName = "星空键道 6",
            assetPath = "preset_schemas/xkjd6_preset.zip",
            license = "GPL-3.0",
            archetypeLabel = "顶功码表 (PUSH_TOPUP_CODE)",
            description = "开源音形顶功方案（xkinput/KeyTao），声韵双拼 + a/e/i/o/u/v 顶功笔画分离，内置 librime-lua topup_processor 原生顶屏。"
        ),
        PresetSchemaInfo(
            id = "wubi86",
            displayName = "五笔 86",
            assetPath = "preset_schemas/wubi86_preset.zip",
            license = "LGPL-3.0",
            archetypeLabel = "纯形定长 (FIXED_SHAPECODE)",
            description = "经典 86 版五笔字型码表（rime/rime-wubi），四码定长空码不乱切分，第 5 码自动顶首选上屏。"
        )
    )
}
