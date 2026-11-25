package com.typefree.ime.network

import android.util.Log
import com.typefree.core.offline.ExecutionSource
import com.typefree.ime.data.VoiceLatencyTrace
import com.typefree.ime.data.VoiceTraceStage

object VoiceLatencyTraceAssembler {

    private const val PERF_LOG_TAG = "TypeFreePerf"

    fun assemble(
        micInitMs: Long,
        recordingDurationMs: Long,
        firstPartialMs: Long = 0L,
        hadStreamingSession: Boolean = false,
        stopIoMs: Long = 0L,
        commitMs: Long = 0L,
        measuredPostReleaseMs: Long = 0L,
        exec: PipelineExecutionResult,
        hostAppName: String = "",
        commitTargetLabelZh: String = ""
    ): VoiceLatencyTrace {
        val isLocalAsr = exec.executionSource == ExecutionSource.LOCAL ||
            exec.executionSource == ExecutionSource.LOCAL_RAW_PASSTHROUGH ||
            exec.executionSource == ExecutionSource.HYBRID_LOCAL_ASR_CLOUD_LLM
        val isLocalLlm = exec.executionSource == ExecutionSource.LOCAL
        val isMultimodalDirect = exec.audioEncodeMs > 0L && exec.sttLatencyMs <= 0L

        val rawSttClean = exec.sttProviderLabel
            .removePrefix("本机 ")
            .removePrefix("本機 ")
            .removePrefix("Local ")
            .trim()
        val (sttZh, sttEn, sttTw) = when {
            isLocalAsr -> {
                val base = rawSttClean.ifBlank { "离线语音" }
                val baseEn = when (base) {
                    "离线语音", "本机离线" -> "Offline"
                    "Zipformer 双语" -> "Zipformer Bilingual"
                    "Paraformer 流式" -> "Paraformer"
                    else -> base
                }
                val baseTw = base.replace("双语", "雙語").replace("流式", "串流").replace("离线语音", "離線語音")
                Triple(
                    if (exec.sttProviderLabel.startsWith("本机 ")) "本机 $base" else base,
                    if (exec.sttProviderLabel.startsWith("本机 ") || baseEn == "Offline") "Local $baseEn" else baseEn,
                    if (exec.sttProviderLabel.startsWith("本机 ")) "本機 $baseTw" else baseTw
                )
            }
            rawSttClean == "豆包流式" -> Triple("豆包流式", "Doubao", "豆包串流")
            rawSttClean == "百炼流式" -> Triple("百炼流式", "Bailian", "百煉串流")
            rawSttClean == "硅基 SenseVoice" -> Triple("硅基 SenseVoice", "SiliconFlow SenseVoice", "矽基 SenseVoice")
            rawSttClean.isNotBlank() -> Triple(rawSttClean, rawSttClean, rawSttClean)
            else -> Triple("STT", "STT", "STT")
        }

        val modelShort = exec.modelName
            .substringAfterLast('/')
            .removePrefix("本机 ")
            .removePrefix("本機 ")
            .removePrefix("Local ")
            .trim()
            .ifBlank {
                exec.modeSummary.substringAfterLast('+').removePrefix("本机 ").trim().ifBlank { "LLM" }
            }
        val bareNumericLocal = isLocalLlm && modelShort.firstOrNull()?.isDigit() == true
        val modelZh = if (bareNumericLocal) "本机 $modelShort" else modelShort
        val modelEn = if (bareNumericLocal) "Local $modelShort" else modelShort
        val modelTw = if (bareNumericLocal) "本機 $modelShort" else modelShort

        val (wsZh, wsEn, wsTw) = when {
            isLocalAsr -> Triple(":local_ai 常驻", ":local_ai ready", ":local_ai 常駐")
            hadStreamingSession || exec.usedStreamingAsr -> Triple(
                if (sttZh.endsWith("流式")) sttZh else "$sttZh 流式",
                if (sttEn.endsWith("stream", ignoreCase = true)) sttEn else "$sttEn stream",
                if (sttTw.endsWith("串流")) sttTw else "$sttTw 串流"
            )
            isMultimodalDirect -> Triple("多模态直传", "Multimodal direct", "多模態直傳")
            recordingDurationMs > 0L -> Triple("整包录音", "Buffered audio", "整包錄音")
            else -> Triple("文本直调", "Direct text", "文字直調")
        }

        val (hintZh, hintEn, hintTw) = when {
            firstPartialMs > 0L -> Triple(
                "首字 @ ${firstPartialMs}ms",
                "1st word @ ${firstPartialMs}ms",
                "首字 @ ${firstPartialMs}ms"
            )
            exec.usedStreamingAsr -> Triple("流式边说边转", "Live streaming ASR", "串流邊說邊轉")
            isLocalAsr -> Triple("纯端侧听音", "On-device ASR", "純本機聽音")
            isMultimodalDirect -> Triple("一步听音+排版", "1-step audio LLM", "一步聽音+排版")
            recordingDurationMs > 0L -> Triple("松手后上传", "Upload on release", "鬆手後上傳")
            else -> Triple("免录音改写", "No-audio text pass", "免錄音改寫")
        }

        val stages = ArrayList<VoiceTraceStage>(5)

        // Stage 0 (toneStep 0 - Obsidian Muted): Audio Stop / Buffer / Multimodal Encode
        if (isMultimodalDirect) {
            val encTotal = (stopIoMs.coerceAtLeast(0L) + exec.audioEncodeMs.coerceAtLeast(1L)).coerceAtLeast(1L)
            stages += VoiceTraceStage(
                key = "encode",
                toneStep = 0,
                labelZh = "收音与音频封装",
                labelEn = "Audio Stop & Encode",
                labelTw = "收音與音訊封裝",
                durationMs = encTotal,
                detailZh = "收音收尾 ${stopIoMs.coerceAtLeast(0L)}ms + 音频压缩与编码 ${exec.audioEncodeMs}ms",
                detailEn = "Audio stop ${stopIoMs.coerceAtLeast(0L)}ms + AAC/Base64 encode ${exec.audioEncodeMs}ms",
                detailTw = "收音收尾 ${stopIoMs.coerceAtLeast(0L)}ms + 音訊壓縮與編碼 ${exec.audioEncodeMs}ms"
            )
        } else if (stopIoMs > 0L || recordingDurationMs > 0L) {
            val ioMs = stopIoMs.coerceAtLeast(1L)
            stages += if (isLocalAsr) {
                VoiceTraceStage(
                    key = "io",
                    toneStep = 0,
                    labelZh = "PCM 传本机进程",
                    labelEn = "PCM to Local Process",
                    labelTw = "PCM 傳本機行程",
                    durationMs = ioMs,
                    detailZh = "停止录音并将音频缓冲传至 :local_ai 推理进程",
                    detailEn = "Stop AudioRecord and hand off PCM buffer to :local_ai process",
                    detailTw = "停止錄音並將音訊緩衝傳至 :local_ai 推理行程"
                )
            } else {
                VoiceTraceStage(
                    key = "io",
                    toneStep = 0,
                    labelZh = "收音落盘",
                    labelEn = "Audio Stop & Flush",
                    labelTw = "收音寫入",
                    durationMs = ioMs,
                    detailZh = "AudioRecord 停止采集与 WAV 尾帧刷盘",
                    detailEn = "Stop AudioRecord capture and flush WAV header/frames",
                    detailTw = "AudioRecord 停止擷取與 WAV 尾幀寫入"
                )
            }
        }

        // Stage 1 (toneStep 1 - Deep Teal): Stage-1 ASR / STT
        if (!isMultimodalDirect && (exec.sttLatencyMs > 0L || exec.usedStreamingAsr)) {
            val sttMs = exec.sttLatencyMs.coerceAtLeast(1L)
            stages += if (exec.usedStreamingAsr) {
                VoiceTraceStage(
                    key = "stt",
                    toneStep = 1,
                    labelZh = "$sttZh 尾包",
                    labelEn = "$sttEn Tail",
                    labelTw = "$sttTw 尾包",
                    durationMs = sttMs,
                    detailZh = "$sttZh 流式通道在说话期间已并行转出主体，松手仅等待尾包确认",
                    detailEn = "$sttEn streamed live during speech; waited ${sttMs}ms after release for final packet",
                    detailTw = "$sttTw 串流通道於說話期間已並行轉出主體，鬆手僅等待尾包確認"
                )
            } else {
                val asrLabelEn = if (sttEn.endsWith("ASR", ignoreCase = true) ||
                    sttEn.endsWith("STT", ignoreCase = true)
                ) {
                    sttEn
                } else {
                    "$sttEn ASR"
                }
                VoiceTraceStage(
                    key = "stt",
                    toneStep = 1,
                    labelZh = "$sttZh 识别",
                    labelEn = asrLabelEn,
                    labelTw = "$sttTw 識別",
                    durationMs = sttMs,
                    detailZh = if (isLocalAsr) {
                        "$sttZh 本机离线语音转写耗时 ${sttMs}ms"
                    } else {
                        "$sttZh 整段音频上传与语音识别耗时 ${sttMs}ms"
                    },
                    detailEn = if (isLocalAsr) {
                        "On-device $sttEn transcription took ${sttMs}ms"
                    } else {
                        "Full-audio upload & $sttEn transcription took ${sttMs}ms"
                    },
                    detailTw = if (isLocalAsr) {
                        "$sttTw 本機離線語音轉寫耗時 ${sttMs}ms"
                    } else {
                        "$sttTw 整段音訊上傳與語音識別耗時 ${sttMs}ms"
                    }
                )
            }
        }

        // Stage 2 & 3 (toneStep 2 Emerald & toneStep 3 Mint Light): Stage-2 LLM / SLM / Multimodal
        if (exec.llmLatencyMs > 0L) {
            val canSplitTtft = exec.llmTtftMs > 0L && exec.llmGenMs >= 8L
            val cachePct = exec.tokenUsage?.let { u ->
                if (u.promptTokens > 0L && u.cachedTokens > 0L) {
                    ((u.cachedTokens * 100L) / u.promptTokens).coerceIn(1L, 100L)
                } else {
                    0L
                }
            } ?: 0L
            val cacheNoteZh = if (cachePct > 0L) "（Prompt 缓存命中 $cachePct%）" else ""
            val cacheNoteEn = if (cachePct > 0L) " (Prompt cache hit $cachePct%)" else ""

            if (canSplitTtft) {
                val ttft = exec.llmTtftMs.coerceAtLeast(1L)
                val gen = (exec.llmLatencyMs - ttft).coerceAtLeast(exec.llmGenMs).coerceAtLeast(1L)
                stages += if (isLocalLlm) {
                    VoiceTraceStage(
                        key = "ttft",
                        toneStep = 2,
                        labelZh = "$modelZh 预填充",
                        labelEn = "$modelEn Prefill",
                        labelTw = "$modelTw 預填充",
                        durationMs = ttft,
                        detailZh = "本机 llama.cpp 提示词预填充 (Prefill) 耗时 ${ttft}ms",
                        detailEn = "On-device llama.cpp prompt prefill took ${ttft}ms",
                        detailTw = "本機 llama.cpp 提示詞預填充耗時 ${ttft}ms"
                    )
                } else {
                    VoiceTraceStage(
                        key = "ttft",
                        toneStep = 2,
                        labelZh = "$modelZh 首包",
                        labelEn = "$modelEn TTFT",
                        labelTw = "$modelTw 首包",
                        durationMs = ttft,
                        detailZh = "$modelZh 网络请求与首 Token 响应 (TTFT) ${ttft}ms",
                        detailEn = "$modelEn network & time-to-first-token (TTFT) ${ttft}ms",
                        detailTw = "$modelTw 網路請求與首 Token 回應 (TTFT) ${ttft}ms"
                    )
                }

                val localPerfSuffix = exec.localMetricsSummary.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
                stages += if (isLocalLlm) {
                    VoiceTraceStage(
                        key = "gen",
                        toneStep = 3,
                        labelZh = "$modelZh 净写",
                        labelEn = "$modelEn Decode",
                        labelTw = "$modelTw 淨寫",
                        durationMs = gen,
                        detailZh = "本机模型流式解码生成耗时 ${gen}ms$localPerfSuffix",
                        detailEn = "On-device token generation took ${gen}ms$localPerfSuffix",
                        detailTw = "本機模型解碼生成耗時 ${gen}ms$localPerfSuffix"
                    )
                } else {
                    VoiceTraceStage(
                        key = "gen",
                        toneStep = 3,
                        labelZh = "$modelZh 吐字",
                        labelEn = "$modelEn Stream",
                        labelTw = "$modelTw 輸出",
                        durationMs = gen,
                        detailZh = "$modelZh 流式输出定稿文本耗时 ${gen}ms$cacheNoteZh",
                        detailEn = "$modelEn streaming output generation took ${gen}ms$cacheNoteEn",
                        detailTw = "$modelTw 串流輸出定稿文字耗時 ${gen}ms"
                    )
                }
            } else {
                val llmMs = exec.llmLatencyMs.coerceAtLeast(1L)
                stages += VoiceTraceStage(
                    key = "llm",
                    toneStep = 2,
                    labelZh = if (isMultimodalDirect) "$modelZh 音频理解" else "$modelZh 润色",
                    labelEn = if (isMultimodalDirect) "$modelEn Audio LLM" else "$modelEn Polish",
                    labelTw = if (isMultimodalDirect) "$modelTw 音訊理解" else "$modelTw 潤飾",
                    durationMs = llmMs,
                    detailZh = "$modelZh 完整请求与文本生成耗时 ${llmMs}ms$cacheNoteZh",
                    detailEn = "$modelEn full request & generation took ${llmMs}ms$cacheNoteEn",
                    detailTw = "$modelTw 完整請求與文字生成耗時 ${llmMs}ms"
                )
            }
        }

        // Stage 4 (toneStep 4 - Crisp Monochrome Commit): Host Editor Insertion
        if (commitMs > 0L) {
            val targetPrefix = commitTargetLabelZh.ifBlank {
                hostAppName.takeIf { it.isNotBlank() && it.length <= 12 } ?: "输入框"
            }
            stages += VoiceTraceStage(
                key = "commit",
                toneStep = 4,
                labelZh = "${targetPrefix}上屏",
                labelEn = "Editor Commit",
                labelTw = "${targetPrefix}上屏",
                durationMs = commitMs,
                detailZh = "选区状态核验并通过 InputConnection 将定稿写入光标处",
                detailEn = "Verify editor selection and commit final text via InputConnection",
                detailTw = "選區狀態核驗並透過 InputConnection 將定稿寫入游標處"
            )
        }

        val stageSum = stages.sumOf { it.durationMs }.coerceAtLeast(1L)
        val effectivePostReleaseMs = maxOf(measuredPostReleaseMs, exec.latencyMs, stageSum).coerceAtLeast(1L)

        val tokRate = Regex("""(\d+(?:\.\d+)?\s*tok/s)""").find(exec.localMetricsSummary)?.groupValues?.getOrNull(1)
        val slowest = stages.maxByOrNull { it.durationMs }
        val isSlow = effectivePostReleaseMs > 1200L
        val (badgeZh, badgeEn, badgeTw) = when {
            !tokRate.isNullOrBlank() -> Triple("本机 $tokRate", "Local $tokRate", "本機 $tokRate")
            effectivePostReleaseMs <= 780L -> Triple("链路顺畅", "Fast Path", "鏈路順暢")
            slowest != null -> Triple(
                "主要耗时：${slowest.labelZh}",
                "Main: ${slowest.labelEn}",
                "主要耗時：${slowest.labelTw}"
            )
            else -> Triple("已完成", "Completed", "已完成")
        }

        val trace = VoiceLatencyTrace(
            micInitMs = micInitMs.coerceAtLeast(0L),
            wsSetupZh = wsZh,
            wsSetupEn = wsEn,
            wsSetupTw = wsTw,
            recordingDurationMs = recordingDurationMs.coerceAtLeast(0L),
            firstPartialMs = firstPartialMs.coerceAtLeast(0L),
            streamHintZh = hintZh,
            streamHintEn = hintEn,
            streamHintTw = hintTw,
            postReleaseTotalMs = effectivePostReleaseMs,
            summaryBadgeZh = badgeZh,
            summaryBadgeEn = badgeEn,
            summaryBadgeTw = badgeTw,
            isSlowWarning = isSlow,
            stages = stages
        )

        // Emit zero-PII single-line structured latency trace to Android Logcat (`adb logcat -s TypeFreePerf`)
        runCatching {
            val stageBreakdown = stages.joinToString("+") { "${it.key}:${it.durationMs}ms" }
            Log.i(
                PERF_LOG_TAG,
                "route=\"${exec.modeSummary}\" mic_init=${trace.micInitMs}ms rec=${trace.recordingDurationMs}ms " +
                    "first_partial=${trace.firstPartialMs}ms post_release=${trace.postReleaseTotalMs}ms [$stageBreakdown]"
            )
        }

        return trace
    }
}
