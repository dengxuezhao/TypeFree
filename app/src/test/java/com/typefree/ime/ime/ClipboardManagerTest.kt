package com.typefree.ime.ime

import com.typefree.ime.data.AppRepository
import com.typefree.ime.data.ClipboardEntry
import com.typefree.ime.ime.clipboard.ClipboardFilterTab
import com.typefree.ime.ime.clipboard.ClipboardSmartExtractor
import com.typefree.ime.ime.clipboard.SmartClipKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardManagerTest {

    @Test
    fun `extracts 6-digit OTP from Chinese SMS while skipping year prefix`() {
        val sms = "【飞书】2026年安全提醒：您的登录验证码是 638291，5分钟内有效，请勿泄露。"
        val extracted = ClipboardSmartExtractor.extractPrimary(sms)
        assertNotNull(extracted)
        assertEquals(SmartClipKind.OTP_CODE, extracted?.kind)
        assertEquals("638291", extracted?.extractedValue)
        assertEquals("🔢 仅填 638291", extracted?.chipLabel)
    }

    @Test
    fun `extracts English verification code`() {
        val msg = "Your GitHub authentication passcode is 904812. Do not share it."
        val extracted = ClipboardSmartExtractor.extractPrimary(msg)
        assertNotNull(extracted)
        assertEquals(SmartClipKind.OTP_CODE, extracted?.kind)
        assertEquals("904812", extracted?.extractedValue)
    }

    @Test
    fun `extracts URL cleanly without trailing Chinese punctuation`() {
        val msg = "安卓小鹤官网地址：https://flypy.cc/download，点此查看教程。"
        val extracted = ClipboardSmartExtractor.extractPrimary(msg)
        assertNotNull(extracted)
        assertEquals(SmartClipKind.URL, extracted?.kind)
        assertEquals("https://flypy.cc/download", extracted?.extractedValue)
    }

    @Test
    fun `extracts email and mainland phone number`() {
        val emailMsg = "请发送简历至 dev.team@typefree.org 谢谢"
        val emailExt = ClipboardSmartExtractor.extractPrimary(emailMsg)
        assertEquals(SmartClipKind.EMAIL, emailExt?.kind)
        assertEquals("dev.team@typefree.org", emailExt?.extractedValue)

        val phoneMsg = "收件人王先生 联系电话 +86 138-0013-8000 顺丰到付"
        val phoneExt = ClipboardSmartExtractor.extractPrimary(phoneMsg)
        assertEquals(SmartClipKind.PHONE, phoneExt?.kind)
        assertEquals("13800138000", phoneExt?.extractedValue)

        assertNull(ClipboardSmartExtractor.extractPrimary("今天下午三点开产品评审会"))
    }

    @Test
    fun `filterItems separates ALL PINNED and SMART tabs accurately`() {
        val entries = listOf(
            ClipboardEntry(id = "1", text = "北京市海淀区中关村软件园", timestamp = 300L, isPinned = true),
            ClipboardEntry(id = "2", text = "【顺丰】取件码 884216", timestamp = 200L, isPinned = false),
            ClipboardEntry(id = "3", text = "普通临时备注文本", timestamp = 100L, isPinned = false)
        )

        assertEquals(3, ClipboardSmartExtractor.filterItems(entries, ClipboardFilterTab.ALL).size)
        assertEquals(
            listOf("1"),
            ClipboardSmartExtractor.filterItems(entries, ClipboardFilterTab.PINNED).map { it.id }
        )
        assertEquals(
            listOf("2"),
            ClipboardSmartExtractor.filterItems(entries, ClipboardFilterTab.SMART).map { it.id }
        )
    }

    @Test
    fun `upsertClipboardList deduplicates preserves pin state and caps unpinned at 40`() {
        var list = emptyList<ClipboardEntry>()

        // Seed 2 pinned items
        val (afterFirst, p1) = AppRepository.upsertClipboardList(list, "常用地址A", forcePinned = true, nowMs = 100L)
        assertNotNull(p1)
        assertEquals("常用地址A", p1?.text)
        list = afterFirst
        list = AppRepository.upsertClipboardList(list, "常用邮箱B", forcePinned = true, nowMs = 200L).first

        // Re-copying an already pinned item with forcePinned=false MUST preserve its isPinned=true status!
        list = AppRepository.upsertClipboardList(list, "常用地址A", forcePinned = false, nowMs = 300L).first
        val pinnedA = list.first { it.text == "常用地址A" }
        assertTrue(pinnedA.isPinned)
        assertEquals(300L, pinnedA.timestamp)

        // Add 45 unpinned items -> unpinned should cap at 40, while all 2 pinned items remain at the top!
        for (i in 1..45) {
            list = AppRepository.upsertClipboardList(
                existing = list,
                rawText = "临时复制条目 #$i",
                forcePinned = false,
                nowMs = 1000L + i
            ).first
        }

        assertEquals(42, list.size)
        assertEquals(2, list.count { it.isPinned })
        assertEquals(40, list.count { !it.isPinned })
        assertTrue(list[0].isPinned && list[1].isPinned)
        assertEquals("临时复制条目 #45", list[2].text)
    }
}
