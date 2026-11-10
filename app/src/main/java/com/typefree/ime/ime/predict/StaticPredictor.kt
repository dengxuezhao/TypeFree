package com.typefree.ime.ime.predict

import android.content.Context
import com.typefree.ime.ime.flypy.FlypyCandidate
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/**
 * L1 Static Predictor for TypeFree v2.7:
 * - Provides `~0ms` local Chinese next-word / phrase predictions via built-in high-frequency
 *   Chinese bigram collocations and compound-word prefix continuations.
 * - Supports loading extended predictions from `filesDir/rime/shared/predict.db` or TSV files.
 */
class StaticPredictor(
    initialCustomBigrams: Map<String, List<String>> = emptyMap()
) {

    private val customBigrams = ConcurrentHashMap<String, List<String>>()

    init {
        for ((k, v) in initialCustomBigrams) {
            val cleanKey = k.trim()
            val cleanValues = v.map { it.trim() }.filter { it.isNotEmpty() && it != cleanKey }.distinct()
            if (cleanKey.isNotEmpty() && cleanValues.isNotEmpty()) {
                customBigrams[cleanKey] = cleanValues
            }
        }
    }

    /**
     * Loads extended bigram predictions from `filesDir/rime/shared/predict.db` or `predict.tsv` if present.
     */
    fun loadFromContextIfAvailable(context: Context): Int {
        val sharedDir = File(context.filesDir, "rime/shared")
        return loadFromRimeSharedDir(sharedDir)
    }

    fun loadFromRimeSharedDir(sharedDir: File): Int {
        if (!sharedDir.exists() || !sharedDir.isDirectory) return 0
        var loadedCount = 0
        val candidates = listOf(
            File(sharedDir, "predict.tsv"),
            File(sharedDir, "predict.db")
        )
        for (file in candidates) {
            if (file.exists() && file.isFile && file.length() > 0L) {
                loadedCount += runCatching {
                    FileInputStream(file).use { loadFromTsvStream(it) }
                }.getOrDefault(0)
            }
        }
        return loadedCount
    }

    /**
     * Parses tab-separated or space-separated bigram lines:
     * Format A: `prevWord\tnextWord1\tnextWord2...`
     * Format B: `prevWord\tnextWord` (multiple lines with same `prevWord`)
     */
    fun loadFromTsvStream(inputStream: InputStream): Int {
        val grouped = LinkedHashMap<String, MutableList<String>>()
        BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8)).use { reader ->
            var line = reader.readLine()
            while (line != null) {
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                    val parts = trimmed.split('\t')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                    if (parts.size >= 2) {
                        val prev = parts[0]
                        if (PredictionStore.isValidWord(prev)) {
                            val bucket = grouped.getOrPut(prev) { ArrayList() }
                            for (i in 1 until parts.size) {
                                val token = parts[i].substringBefore(':').trim()
                                if (PredictionStore.isValidWord(token) && token != prev && token !in bucket) {
                                    bucket.add(token)
                                }
                            }
                        }
                    }
                }
                line = reader.readLine()
            }
        }
        for ((prev, nextList) in grouped) {
            if (nextList.isNotEmpty()) {
                val existing = customBigrams[prev].orEmpty()
                customBigrams[prev] = (nextList + existing).distinct()
            }
        }
        return grouped.size
    }

    fun predictWords(prevWord: String, limit: Int = DEFAULT_LIMIT): List<String> {
        if (limit <= 0) return emptyList()
        val cleanPrev = prevWord.trim()
        if (!PredictionStore.isValidWord(cleanPrev)) return emptyList()

        val result = ArrayList<String>(limit)
        val seen = HashSet<String>(limit * 2)
        seen.add(cleanPrev) // Never predict the exact same word as prevWord

        fun appendCandidates(items: Iterable<String>): Boolean {
            for (raw in items) {
                val item = raw.trim()
                if (item.isNotEmpty() && PredictionStore.isValidWord(item) && seen.add(item)) {
                    result.add(item)
                    if (result.size >= limit) return true
                }
            }
            return false
        }

        // 1. Custom extended bigrams (from predict.db / predict.tsv or constructor)
        customBigrams[cleanPrev]?.let { if (appendCandidates(it)) return result }

        // 2. Built-in exact bigram table
        BUILTIN_BIGRAMS[cleanPrev]?.let { if (appendCandidates(it)) return result }

        // 3. Compound phrase prefix completion (e.g. "人工" -> "智能", "小鹤" -> "音形")
        if (cleanPrev.length in 1..3) {
            for (phrase in BUILTIN_COMPOUND_PHRASES) {
                if (phrase.length > cleanPrev.length && phrase.startsWith(cleanPrev)) {
                    val suffix = phrase.substring(cleanPrev.length)
                    if (PredictionStore.isValidWord(suffix) && seen.add(suffix)) {
                        result.add(suffix)
                        if (result.size >= limit) return result
                    }
                }
            }
        }

        // 4. Tail character association if prevWord is multi-char
        if (cleanPrev.length >= 2) {
            val tailCharStr = cleanPrev.last().toString()
            customBigrams[tailCharStr]?.let { if (appendCandidates(it)) return result }
            BUILTIN_BIGRAMS[tailCharStr]?.let { if (appendCandidates(it)) return result }
        }

        // 5. Fallback high-frequency Chinese continuations when input contains CJK characters
        if (containsCjk(cleanPrev)) {
            appendCandidates(DEFAULT_CJK_FOLLOWUPS)
        }

        return result
    }

    fun predict(prevWord: String, limit: Int = DEFAULT_LIMIT): List<FlypyCandidate> {
        return predictWords(prevWord, limit).map { FlypyCandidate(text = it) }
    }

    companion object {
        const val DEFAULT_LIMIT = 8

        private fun containsCjk(text: String): Boolean {
            return text.any { ch -> ch.code in 0x4E00..0x9FFF }
        }

        private val DEFAULT_CJK_FOLLOWUPS: List<String> = listOf(
            "的", "了", "是", "在", "可以", "已经", "和", "一下", "我们", "没有"
        )

        private val BUILTIN_COMPOUND_PHRASES: List<String> = listOf(
            "人工智能", "输入法", "小鹤音形", "小鹤双拼", "雾凇拼音",
            "语音识别", "实时写入", "无痕模式", "自动学习", "剪贴板",
            "快捷键", "大模型", "工作群", "会议室", "说明书",
            "好意思", "没问题", "不知道", "怎么样", "为什么",
            "看起来", "接下来", "差不多", "来不及", "一方面"
        )

        val BUILTIN_BIGRAMS: Map<String, List<String>> = mapOf(
            "今天" to listOf("下午", "晚上", "早上", "可以", "已经", "有空", "开会", "辛苦"),
            "明天" to listOf("下午", "上午", "早上", "晚上", "见", "开会", "可以", "再聊"),
            "昨天" to listOf("晚上", "下午", "已经", "发的", "说的", "开会"),
            "现在" to listOf("可以", "开始", "马上", "正在", "过去", "方便", "有空", "出发"),
            "目前" to listOf("已经", "还在", "来看", "没有", "可以", "进展", "情况"),
            "我们" to listOf("可以", "一起", "现在", "今天", "已经", "需要", "先", "这边"),
            "你们" to listOf("可以", "那边", "现在", "今天", "什么时候", "先"),
            "他们" to listOf("已经", "可以", "那边", "今天", "正在", "说"),
            "咱们" to listOf("可以", "今天", "一起", "现在", "先", "下午"),
            "可以" to listOf("的", "先", "直接", "看看", "安排", "发我", "试试", "确认"),
            "已经" to listOf("完成", "发你", "收到", "处理", "确认", "搞定", "好了", "更新"),
            "收到" to listOf("了", "谢谢", "马上", "我先", "稍后", "立办"),
            "谢谢" to listOf("大家", "支持", "理解", "配合", "老师", "老板", "你"),
            "感谢" to listOf("大家", "您的", "支持", "配合", "帮助", "理解"),
            "麻烦" to listOf("你", "大家", "帮我", "确认", "看一下", "发一下", "稍等"),
            "请问" to listOf("一下", "现在", "什么时候", "可以", "有空", "方便", "这个"),
            "如果" to listOf("可以", "有", "没有", "需要", "方便", "大家", "没问题"),
            "因为" to listOf("今天", "现在", "这个", "需要", "时间", "临时", "我们"),
            "所以" to listOf("我们", "今天", "需要", "可以", "先", "建议", "暂时"),
            "但是" to listOf("现在", "我们", "可以", "还需要", "目前", "感觉", "其实"),
            "不过" to listOf("可以", "现在", "我们", "还是", "没关系", "需要先"),
            "虽然" to listOf("但是", "现在", "有点", "已经", "可以"),
            "这个" to listOf("问题", "方案", "项目", "可以", "需要", "时候", "功能", "版本"),
            "那个" to listOf("时候", "问题", "方案", "文件", "地方", "人"),
            "什么" to listOf("时候", "意思", "情况", "问题", "地方", "原因"),
            "怎么" to listOf("样", "办", "回事", "处理", "操作", "安排"),
            "没有" to listOf("问题", "关系", "收到", "办法", "时间", "影响"),
            "需要" to listOf("注意", "确认", "修改", "补充", "支持", "进一步", "大家"),
            "觉得" to listOf("可以", "怎么样", "很不错", "没问题", "还需要", "挺好"),
            "希望" to listOf("大家", "能够", "可以", "尽快", "一切", "顺利"),
            "大家" to listOf("好", "辛苦了", "可以", "一起", "看看", "注意", "下午好"),
            "下午" to listOf("好", "三点", "四点", "两点", "开会", "见", "有空"),
            "上午" to listOf("好", "十点", "九点", "开会", "可以", "见"),
            "晚上" to listOf("好", "见", "一起", "有空", "回去", "吃饭", "八点"),
            "早上" to listOf("好", "八点", "九点", "起来", "见"),
            "开会" to listOf("讨论", "确认", "同步", "时间", "地点", "纪要"),
            "发给" to listOf("我", "你", "大家", "他", "客户", "老板"),
            "看一下" to listOf("这个", "文档", "方案", "问题", "群里", "消息"),
            "没问题" to listOf("的", "我先", "马上", "稍后", "可以"),
            "辛苦" to listOf("了", "大家", "你了", "各位", "老师"),
            "你好" to listOf("请问", "我是", "很高兴", "麻烦", "现在"),
            "您好" to listOf("请问", "这边", "麻烦", "收到", "感谢"),
            "准备" to listOf("好了", "开始", "一下", "开会", "出发", "工作"),
            "工作" to listOf("顺利", "汇报", "安排", "计划", "内容", "进度"),
            "项目" to listOf("进度", "管理", "负责人", "方案", "上线", "推进"),
            "方案" to listOf("设计", "评审", "已经", "可以", "调整", "落地"),
            "问题" to listOf("不大", "已经", "解决", "排查", "反馈", "分析"),
            "时间" to listOf("安排", "可以", "定在", "不够", "节点", "确认"),
            "会议" to listOf("纪要", "室", "时间", "链接", "安排", "内容"),
            "版本" to listOf("更新", "发布", "上线", "升级", "测试", "回滚"),
            "测试" to listOf("通过", "环境", "用例", "结果", "一下", "报告"),
            "确认" to listOf("一下", "收到", "无误", "完毕", "时间", "结果"),
            "处理" to listOf("一下", "完成", "结果", "好", "中", "方案"),
            "安排" to listOf("一下", "好", "时间", "妥当", "人手", "进度"),
            "联系" to listOf("我", "你", "方式", "一下", "沟通", "确认"),
            "沟通" to listOf("一下", "确认", "结果", "顺畅", "细节"),
            "支持" to listOf("一下", "中文", "自定义", "格式", "导出", "导入"),
            "更新" to listOf("一下", "完了", "内容", "版本", "进度", "日志"),
            "稍后" to listOf("发你", "回复", "同步", "联系", "确认", "处理"),
            "马上" to listOf("到", "好", "处理", "发你", "开始", "去"),
            "我" to listOf("们", "先", "已经", "现在", "觉得", "可以", "去", "在"),
            "你" to listOf("们", "好", "可以", "现在", "看看", "先", "觉得", "在"),
            "他" to listOf("们", "的", "已经", "现在", "说", "在"),
            "她" to listOf("们", "的", "已经", "现在", "说", "在"),
            "这" to listOf("个", "里", "边", "是", "样", "次", "些", "周"),
            "那" to listOf("个", "里", "边", "么", "是", "样", "些"),
            "好" to listOf("的", "了", "吧", "像", "看", "多", "久"),
            "是" to listOf("的", "不是", "因为", "可以", "什么", "我", "在"),
            "在" to listOf("哪里", "吗", "一起", "这里", "路上", "开会", "忙"),
            "有" to listOf("没有", "空", "时间", "问题", "一点", "什么", "可能"),
            "不" to listOf("好意思", "用", "可以", "知道", "是", "需要", "会"),
            "很" to listOf("好", "高兴", "快", "多", "棒", "重要", "方便"),
            "先" to listOf("看", "去", "这样", "发", "休息", "处理", "确认"),
            "再" to listOf("见", "说", "看看", "确认", "联系", "发", "试试"),
            "请" to listOf("问", "稍等", "查收", "确认", "看", "放心", "大家"),
            "发" to listOf("送", "给我", "给你", "出来", "消息", "邮件", "文件"),
            "看" to listOf("一下", "看", "到", "起来", "完了", "见"),
            "想" to listOf("要", "问一下", "知道", "法", "看看", "起来"),
            "去" to listOf("哪里", "看看", "吃饭", "开会", "公司", "找"),
            "来" to listOf("了", "不及", "看看", "说", "自", "开会"),
            "做" to listOf("完", "好", "什么", "一个", "出来", "得"),
            "说" to listOf("明", "话", "得对", "一下", "清楚", "完"),
            "要" to listOf("不要", "求", "是", "去", "先", "注意"),
            "会" to listOf("议", "不会", "的", "尽快", "在", "有"),
            "能" to listOf("不能", "够", "力", "帮我", "看到", "理解"),
            "对" to listOf("于", "不对", "的", "不起", "接", "比", "方"),
            "把" to listOf("这个", "文件", "问题", "方案", "它", "时间"),
            "给" to listOf("我", "你", "大家", "他", "客户", "出"),
            "从" to listOf("而", "来", "现在", "这里", "头", "前"),
            "向" to listOf("前", "大家", "上", "你", "汇报", "左"),
            "到" to listOf("了", "底", "时候", "位", "处", "现在"),
            "让" to listOf("我", "我们", "人", "大家", "你", "他"),
            "被" to listOf("动", "人", "发现", "取消", "修改")
        )
    }
}
