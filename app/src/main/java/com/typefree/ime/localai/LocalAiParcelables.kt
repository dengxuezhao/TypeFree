package com.typefree.ime.localai

import android.os.Parcel
import android.os.Parcelable
import com.typefree.core.offline.LocalModelSlot

const val LOCAL_AI_PROTOCOL_VERSION: Int = 1
const val MAX_GEN_REQUEST_BYTES: Int = 64 * 1024
const val MAX_SYSTEM_PROMPT_BYTES: Int = 16 * 1024
const val MAX_ASR_HOTWORDS: Int = 64
const val MAX_ASR_HOTWORD_BYTES: Int = 128

const val SLOT_ASR: Int = 0
const val SLOT_SLM: Int = 1

const val PRIORITY_VOICE: Int = 100
const val PRIORITY_EDIT_WRITE: Int = 60
const val PRIORITY_COMPLETION: Int = 20

const val ERR_UNKNOWN: Int = -1
const val ERR_CANCELLED: Int = 1
const val ERR_PREEMPTED: Int = 2
const val ERR_MODEL_NOT_READY: Int = 3
const val ERR_SLM_UNAVAILABLE: Int = 4
const val ERR_OOM_OR_LOW_MEMORY: Int = 5
const val ERR_INVALID_INPUT: Int = 6
const val ERR_SERVICE_DISCONNECTED: Int = 7
const val ERR_STALE_GENERATION: Int = 8

fun LocalModelSlot.toWireSlot(): Int = when (this) {
    LocalModelSlot.ASR -> SLOT_ASR
    LocalModelSlot.SLM -> SLOT_SLM
}

fun Int.toLocalModelSlotOrNull(): LocalModelSlot? = when (this) {
    SLOT_ASR -> LocalModelSlot.ASR
    SLOT_SLM -> LocalModelSlot.SLM
    else -> null
}

/**
 * Truncates [text] to at most [maxBytes] UTF-8 bytes without splitting multi-byte
 * characters or UTF-16 surrogate pairs.
 */
internal fun truncateUtf8Bytes(text: String, maxBytes: Int): Pair<String, Boolean> {
    if (maxBytes <= 0) return "" to text.isNotEmpty()
    val rawBytes = text.toByteArray(Charsets.UTF_8)
    if (rawBytes.size <= maxBytes) return text to false

    var usedBytes = 0
    var charIndex = 0
    val len = text.length
    while (charIndex < len) {
        val codePoint = Character.codePointAt(text, charIndex)
        val cpBytes = when {
            codePoint <= 0x7F -> 1
            codePoint <= 0x7FF -> 2
            codePoint <= 0xFFFF -> 3
            else -> 4
        }
        if (usedBytes + cpBytes > maxBytes) break
        usedBytes += cpBytes
        charIndex += Character.charCount(codePoint)
    }
    return text.substring(0, charIndex) to true
}

internal data class TruncatedPromptPair(
    val systemPrompt: String,
    val userPrompt: String,
    val totalBytes: Int,
    val wasTruncated: Boolean
)

internal fun enforceGenPromptBudget(
    systemPrompt: String,
    userPrompt: String,
    maxTotalBytes: Int = MAX_GEN_REQUEST_BYTES
): TruncatedPromptPair {
    val safeLimit = maxTotalBytes.coerceAtLeast(0)
    val sysBytes = systemPrompt.toByteArray(Charsets.UTF_8).size
    val userBytes = userPrompt.toByteArray(Charsets.UTF_8).size
    if (sysBytes + userBytes <= safeLimit) {
        return TruncatedPromptPair(
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            totalBytes = sysBytes + userBytes,
            wasTruncated = false
        )
    }

    val sysBudget = minOf(sysBytes, minOf(MAX_SYSTEM_PROMPT_BYTES, safeLimit))
    val (safeSys, sysCut) = truncateUtf8Bytes(systemPrompt, sysBudget)
    val actualSysBytes = safeSys.toByteArray(Charsets.UTF_8).size
    val userBudget = (safeLimit - actualSysBytes).coerceAtLeast(0)
    val (safeUser, userCut) = truncateUtf8Bytes(userPrompt, userBudget)
    val actualUserBytes = safeUser.toByteArray(Charsets.UTF_8).size

    return TruncatedPromptPair(
        systemPrompt = safeSys,
        userPrompt = safeUser,
        totalBytes = actualSysBytes + actualUserBytes,
        wasTruncated = sysCut || userCut
    )
}

data class AsrParams(
    val modelId: String,
    val modelDirPath: String,
    val languageHint: String = "zh",
    val hotwords: List<String> = emptyList(),
    val sampleRateHz: Int = 16_000,
    val incognito: Boolean = false,
    val expectedGeneration: Long = 0L,
    val priority: Int = PRIORITY_VOICE
) : Parcelable {

    val sanitizedHotwords: List<String>
        get() = hotwords
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(MAX_ASR_HOTWORDS)
            .map { truncateUtf8Bytes(it, MAX_ASR_HOTWORD_BYTES).first }
            .toList()

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(modelId)
        dest.writeString(modelDirPath)
        dest.writeString(languageHint)
        dest.writeStringList(sanitizedHotwords)
        dest.writeInt(sampleRateHz)
        dest.writeByte(if (incognito) 1 else 0)
        dest.writeLong(expectedGeneration)
        dest.writeInt(priority)
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<AsrParams> = object : Parcelable.Creator<AsrParams> {
            override fun createFromParcel(source: Parcel): AsrParams {
                val modelId = source.readString().orEmpty()
                val modelDirPath = source.readString().orEmpty()
                val languageHint = source.readString().orEmpty().ifEmpty { "zh" }
                val hotwords = ArrayList<String>()
                source.readStringList(hotwords)
                val sampleRateHz = source.readInt().takeIf { it > 0 } ?: 16_000
                val incognito = source.readByte().toInt() != 0
                val expectedGeneration = source.readLong()
                val priority = source.readInt()
                return AsrParams(
                    modelId = modelId,
                    modelDirPath = modelDirPath,
                    languageHint = languageHint,
                    hotwords = hotwords,
                    sampleRateHz = sampleRateHz,
                    incognito = incognito,
                    expectedGeneration = expectedGeneration,
                    priority = priority
                )
            }

            override fun newArray(size: Int): Array<AsrParams?> = arrayOfNulls(size)
        }
    }
}

/**
 * Cross-process generation request with mandatory <= 64KB UTF-8 prompt budget enforcement
 * to protect the 1MB Binder transaction buffer.
 */
class GenRequest(
    val modelId: String,
    val modelDirPath: String,
    val taskName: String,
    systemPrompt: String,
    userPrompt: String,
    val maxTokens: Int = 256,
    val temperature: Float = 0.2f,
    val priority: Int = PRIORITY_EDIT_WRITE,
    val incognito: Boolean = false,
    val expectedGeneration: Long = 0L,
    val promptTemplateVersion: Int = 1
) : Parcelable {

    private val budgeted: TruncatedPromptPair = enforceGenPromptBudget(
        systemPrompt = systemPrompt,
        userPrompt = userPrompt,
        maxTotalBytes = MAX_GEN_REQUEST_BYTES
    )

    val systemPrompt: String
        get() = budgeted.systemPrompt

    val userPrompt: String
        get() = budgeted.userPrompt

    val totalPromptBytes: Int
        get() = budgeted.totalBytes

    val wasTruncated: Boolean
        get() = budgeted.wasTruncated

    fun copy(
        modelId: String = this.modelId,
        modelDirPath: String = this.modelDirPath,
        taskName: String = this.taskName,
        systemPrompt: String = this.systemPrompt,
        userPrompt: String = this.userPrompt,
        maxTokens: Int = this.maxTokens,
        temperature: Float = this.temperature,
        priority: Int = this.priority,
        incognito: Boolean = this.incognito,
        expectedGeneration: Long = this.expectedGeneration,
        promptTemplateVersion: Int = this.promptTemplateVersion
    ): GenRequest = GenRequest(
        modelId = modelId,
        modelDirPath = modelDirPath,
        taskName = taskName,
        systemPrompt = systemPrompt,
        userPrompt = userPrompt,
        maxTokens = maxTokens,
        temperature = temperature,
        priority = priority,
        incognito = incognito,
        expectedGeneration = expectedGeneration,
        promptTemplateVersion = promptTemplateVersion
    )

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(modelId)
        dest.writeString(modelDirPath)
        dest.writeString(taskName)
        dest.writeString(systemPrompt)
        dest.writeString(userPrompt)
        dest.writeInt(maxTokens)
        dest.writeFloat(temperature)
        dest.writeInt(priority)
        dest.writeByte(if (incognito) 1 else 0)
        dest.writeLong(expectedGeneration)
        dest.writeInt(promptTemplateVersion)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GenRequest) return false
        return modelId == other.modelId &&
            modelDirPath == other.modelDirPath &&
            taskName == other.taskName &&
            systemPrompt == other.systemPrompt &&
            userPrompt == other.userPrompt &&
            maxTokens == other.maxTokens &&
            temperature == other.temperature &&
            priority == other.priority &&
            incognito == other.incognito &&
            expectedGeneration == other.expectedGeneration &&
            promptTemplateVersion == other.promptTemplateVersion
    }

    override fun hashCode(): Int {
        var result = modelId.hashCode()
        result = 31 * result + modelDirPath.hashCode()
        result = 31 * result + taskName.hashCode()
        result = 31 * result + systemPrompt.hashCode()
        result = 31 * result + userPrompt.hashCode()
        result = 31 * result + maxTokens
        result = 31 * result + temperature.hashCode()
        result = 31 * result + priority
        result = 31 * result + incognito.hashCode()
        result = 31 * result + expectedGeneration.hashCode()
        result = 31 * result + promptTemplateVersion
        return result
    }

    override fun toString(): String {
        return "GenRequest(modelId='$modelId', task='$taskName', promptBytes=$totalPromptBytes, " +
            "wasTruncated=$wasTruncated, maxTokens=$maxTokens, priority=$priority, " +
            "incognito=$incognito, expectedGeneration=$expectedGeneration, templateV=$promptTemplateVersion)"
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<GenRequest> = object : Parcelable.Creator<GenRequest> {
            override fun createFromParcel(source: Parcel): GenRequest {
                return GenRequest(
                    modelId = source.readString().orEmpty(),
                    modelDirPath = source.readString().orEmpty(),
                    taskName = source.readString().orEmpty(),
                    systemPrompt = source.readString().orEmpty(),
                    userPrompt = source.readString().orEmpty(),
                    maxTokens = source.readInt(),
                    temperature = source.readFloat(),
                    priority = source.readInt(),
                    incognito = source.readByte().toInt() != 0,
                    expectedGeneration = source.readLong(),
                    promptTemplateVersion = source.readInt()
                )
            }

            override fun newArray(size: Int): Array<GenRequest?> = arrayOfNulls(size)
        }
    }
}

data class RuntimeMetrics(
    val protocolVersion: Int = LOCAL_AI_PROTOCOL_VERSION,
    val modelGeneration: Long = 0L,
    val loadedAsrModelId: String = "",
    val loadedSlmModelId: String = "",
    val totalPssKb: Int = 0,
    val dalvikPssKb: Int = 0,
    val nativePssKb: Int = 0,
    val lastAsrLatencyMs: Long = 0L,
    val lastSlmTtftMs: Long = 0L,
    val lastSlmTokensPerSec: Float = 0f,
    val activeRequestCount: Int = 0,
    val slmEngineAvailable: Boolean = false,
    val pid: Int = 0
) : Parcelable {

    val totalPssMb: Float
        get() = totalPssKb / 1024f

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(protocolVersion)
        dest.writeLong(modelGeneration)
        dest.writeString(loadedAsrModelId)
        dest.writeString(loadedSlmModelId)
        dest.writeInt(totalPssKb)
        dest.writeInt(dalvikPssKb)
        dest.writeInt(nativePssKb)
        dest.writeLong(lastAsrLatencyMs)
        dest.writeLong(lastSlmTtftMs)
        dest.writeFloat(lastSlmTokensPerSec)
        dest.writeInt(activeRequestCount)
        dest.writeByte(if (slmEngineAvailable) 1 else 0)
        dest.writeInt(pid)
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<RuntimeMetrics> = object : Parcelable.Creator<RuntimeMetrics> {
            override fun createFromParcel(source: Parcel): RuntimeMetrics {
                return RuntimeMetrics(
                    protocolVersion = source.readInt(),
                    modelGeneration = source.readLong(),
                    loadedAsrModelId = source.readString().orEmpty(),
                    loadedSlmModelId = source.readString().orEmpty(),
                    totalPssKb = source.readInt(),
                    dalvikPssKb = source.readInt(),
                    nativePssKb = source.readInt(),
                    lastAsrLatencyMs = source.readLong(),
                    lastSlmTtftMs = source.readLong(),
                    lastSlmTokensPerSec = source.readFloat(),
                    activeRequestCount = source.readInt(),
                    slmEngineAvailable = source.readByte().toInt() != 0,
                    pid = source.readInt()
                )
            }

            override fun newArray(size: Int): Array<RuntimeMetrics?> = arrayOfNulls(size)
        }
    }
}
