package com.typefree.ime.ime

import com.typefree.ime.ime.flypy.FlypyDictionary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class CustomRimeDictionaryTest {

    @Test
    fun parseCustomStreams_supportsStandardRimePriorityAndDzReverse() {
        val dictTsv = """
            uijm	时间	实践	世纪
            xhyx	小鹤音形
            test	测试挂载
        """.trimIndent()

        val dzTsv = """
            uiui	时
            jmjm	间
            xyxy	形
        """.trimIndent()

        val dict = FlypyDictionary.loadFromStreams(
            ByteArrayInputStream(dictTsv.toByteArray(Charsets.UTF_8)),
            ByteArrayInputStream(dzTsv.toByteArray(Charsets.UTF_8))
        )

        assertTrue(dict.isLoaded)
        assertEquals(3, dict.codeCount)
        assertEquals(3, dict.reverseCharCount)

        val uijmCandidates = dict.lookupExact("uijm")
        assertEquals(listOf("时间", "实践", "世纪"), uijmCandidates.map { it.text })

        val wildcardSound = dict.queryWildcard("ui``")
        assertTrue(wildcardSound.any { it.text == "时" && it.codeHint == "uiui" })
    }

    @Test
    fun emptyStream_handlesGracefully() {
        val emptyDict = FlypyDictionary.loadFromStreams(
            ByteArrayInputStream("".toByteArray(Charsets.UTF_8)),
            ByteArrayInputStream("".toByteArray(Charsets.UTF_8))
        )
        assertFalse(emptyDict.isLoaded)
        assertEquals(0, emptyDict.codeCount)
        assertEquals(0, emptyDict.reverseCharCount)
        assertTrue(emptyDict.lookupExact("abc").isEmpty())
    }
}
