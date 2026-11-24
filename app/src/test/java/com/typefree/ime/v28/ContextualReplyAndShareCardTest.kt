package com.typefree.ime.v28

import com.typefree.ime.data.AppSettings
import com.typefree.ime.data.DefaultPresets
import com.typefree.ime.data.ToneStyle
import com.typefree.ime.ime.ImeLogic
import com.typefree.ime.ime.clipboard.ClipboardBigBangTokenizer
import com.typefree.ime.ime.clipboard.ClipboardSmartExtractor
import com.typefree.ime.ime.clipboard.SmartClipKind
import com.typefree.ime.network.PromptBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextualReplyAndShareCardTest {

    @Test
    fun `v30 default attitudes are equal-footing and PromptBuilder combines selected attitude with spoken detail`() {
        val defaults = DefaultPresets.defaultReplyAttitudes()
        assertEquals(6, defaults.size)
        assertEquals("👌 行 / 好的", defaults[0].label)
        assertEquals("🙅 算了 / 婉拒", defaults[1].label)
        assertEquals("🤔 啥情况 / 追问", defaults[2].label)
        assertEquals("😂 接梗 / 调侃", defaults[3].label)
        assertEquals("⚡ 硬气回怼 / 催办", defaults[4].label)
        assertEquals("💼 正式得体", defaults[5].label)
        assertEquals(6, ImeLogic.smartReplyAttitudePresets.size)

        // Default liveWriteToEditor is true in v3.0
        assertTrue(AppSettings().liveWriteToEditor)

        val style = ToneStyle(
            id = "casual",
            name = "日常松弛",
            emoji = "💬",
            summary = "自然口语",
            promptInstruction = "自然日常聊天语气"
        )
        val (sysPrompt, userPrompt) = PromptBuilder.buildHelpMeWritePrompts(
            voiceInstruction = "跟他说我在外地出差，周日回去再处理",
            style = style,
            targetAppName = "微信",
            dictionary = emptyList(),
            surroundingBeforeCursor = "",
            attitudeInstruction = defaults[1].instruction
        )
        assertSame(PromptBuilder.HELP_ME_WRITE_SYSTEM_PROMPT, sysPrompt)
        assertTrue(userPrompt.contains("【选定回复态度】："))
        assertTrue(userPrompt.contains("【用户语音补充细节】：跟他说我在外地出差，周日回去再处理"))
    }

    @Test
    fun `v30 Smart Clipboard 2_0 extracts Cainiao Fengchao pickup codes tracking numbers multi-entities and BigBang tokens`() {
        // 1. Fengchao 6-digit pickup code
        val fengchaoSms = "【丰巢】凭取件码425666至金泰和序南门收发室内丰巢柜取件。有疑问联系快递员13800138000"
        val fcAll = ClipboardSmartExtractor.extractAll(fengchaoSms)
        assertEquals(2, fcAll.size)
        assertEquals(SmartClipKind.PICKUP_CODE, fcAll[0].kind)
        assertEquals("425666", fcAll[0].extractedValue)
        assertEquals("📦 取件码 425666", fcAll[0].chipLabel)
        assertEquals(SmartClipKind.PHONE, fcAll[1].kind)
        assertEquals("13800138000", fcAll[1].extractedValue)

        // 2. Cainiao hyphenated shelf pickup code (`3-2-1048` and `A05-2019`)
        val cainiaoSms = "【菜鸟驿站】您的顺丰包裹SF1528394810293已到站，请凭取件码3-2-1048到金泰和序驿站领取"
        val cnAll = ClipboardSmartExtractor.extractAll(cainiaoSms)
        assertTrue(cnAll.any { it.kind == SmartClipKind.PICKUP_CODE && it.extractedValue == "3-2-1048" })
        assertTrue(cnAll.any { it.kind == SmartClipKind.TRACKING_NUMBER && it.extractedValue == "SF1528394810293" })

        // 3. Multi-entity contact + phone + address block
        val shippingInfo = "收件人：张先生 联系电话：13912345678 收货地址：浙江省杭州市余杭区文一西路969号阿里巴巴西溪园区"
        val shipAll = ClipboardSmartExtractor.extractAll(shippingInfo)
        assertTrue(shipAll.any { it.kind == SmartClipKind.PHONE && it.extractedValue == "13912345678" })
        assertTrue(shipAll.any { it.kind == SmartClipKind.ADDRESS && it.extractedValue.contains("浙江省杭州市余杭区") })

        // 4. BigBang Word & Entity Tokenizer
        val tokens = ClipboardBigBangTokenizer.tokenize(fengchaoSms)
        assertTrue(tokens.any { it.text == "丰巢" })
        assertTrue(tokens.any { it.text == "425666" && it.isEntity })
        assertTrue(tokens.any { it.text == "13800138000" && it.isEntity })
        val entityIndices = tokens.filter { it.isEntity }.map { it.index }.toSet()
        assertEquals("425666 13800138000", ClipboardBigBangTokenizer.joinSelectedTokens(tokens, entityIndices))
    }
}
