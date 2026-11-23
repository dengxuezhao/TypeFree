package com.typefree.ime.localai

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.typefree.core.offline.OfflineModelCatalog
import com.typefree.core.offline.RamTier

data class DeviceMemorySnapshot(
    val totalRamBytes: Long,
    val availRamBytes: Long,
    val lowMemory: Boolean,
    val thresholdBytes: Long = 0L
) {
    val totalRamMb: Long
        get() = (totalRamBytes / BYTES_PER_MB).coerceAtLeast(0L)

    val availRamMb: Long
        get() = (availRamBytes / BYTES_PER_MB).coerceAtLeast(0L)

    companion object {
        const val BYTES_PER_MB: Long = 1024L * 1024L
    }
}

data class RuntimeLoadCheckResult(
    val allowed: Boolean,
    val ramTier: RamTier,
    val availRamMb: Long,
    val requiredRamMb: Long,
    val reason: String = ""
)

data class ThermalStateSnapshot(
    val isThrottled: Boolean,
    val headroom: Float? = null,
    val thermalStatus: Int = 0,
    val reason: String = ""
)

/**
 * Resolves device RAM tier from [ActivityManager.MemoryInfo.totalMem] (physical RAM, excluding OEM swap),
 * checks runtime available memory / lowMemory before loading weights, and queries
 * [PowerManager.getThermalHeadroom] / thermal status for thermal degradation protection.
 */
object DeviceRamTierResolver {

    const val DEFAULT_SAFETY_MARGIN_MB: Int = 256
    const val THERMAL_HEADROOM_THROTTLE_THRESHOLD: Float = 0.85f
    const val DEFAULT_THERMAL_FORECAST_SECONDS: Int = 10

    fun resolveRamTierFromBytes(totalRamBytes: Long): RamTier {
        val totalMb = (totalRamBytes / DeviceMemorySnapshot.BYTES_PER_MB).coerceAtLeast(0L)
        return OfflineModelCatalog.ramTierFromTotalMb(totalMb)
    }

    fun snapshotMemory(context: Context): DeviceMemorySnapshot {
        val am = runCatching {
            context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        }.getOrNull()
        val info = ActivityManager.MemoryInfo()
        if (am != null) {
            runCatching { am.getMemoryInfo(info) }
        }
        return DeviceMemorySnapshot(
            totalRamBytes = info.totalMem,
            availRamBytes = info.availMem,
            lowMemory = info.lowMemory,
            thresholdBytes = info.threshold
        )
    }

    fun resolveRamTier(context: Context): RamTier {
        val snapshot = snapshotMemory(context)
        return resolveRamTierFromBytes(snapshot.totalRamBytes)
    }

    /**
     * Checks whether the device has enough runtime memory to safely load a model requiring [approxRamMb].
     * Replaces deprecated `TRIM_MEMORY_RUNNING_LOW / CRITICAL` callbacks on Android 14/15+.
     */
    fun canLoadModel(
        snapshot: DeviceMemorySnapshot,
        approxRamMb: Int,
        safetyMarginMb: Int = DEFAULT_SAFETY_MARGIN_MB
    ): RuntimeLoadCheckResult {
        val tier = resolveRamTierFromBytes(snapshot.totalRamBytes)
        val requiredMb = (approxRamMb.coerceAtLeast(0) + safetyMarginMb.coerceAtLeast(0)).toLong()
        if (snapshot.lowMemory) {
            return RuntimeLoadCheckResult(
                allowed = false,
                ramTier = tier,
                availRamMb = snapshot.availRamMb,
                requiredRamMb = requiredMb,
                reason = "系统处于低内存状态（lowMemory=true），暂停加载离线模型以防 OOM"
            )
        }
        if (snapshot.availRamMb < requiredMb) {
            return RuntimeLoadCheckResult(
                allowed = false,
                ramTier = tier,
                availRamMb = snapshot.availRamMb,
                requiredRamMb = requiredMb,
                reason = "当前可用内存不足（可用 ${snapshot.availRamMb}MB < 需要 ${requiredMb}MB）"
            )
        }
        return RuntimeLoadCheckResult(
            allowed = true,
            ramTier = tier,
            availRamMb = snapshot.availRamMb,
            requiredRamMb = requiredMb
        )
    }

    fun canLoadModel(
        context: Context,
        approxRamMb: Int,
        safetyMarginMb: Int = DEFAULT_SAFETY_MARGIN_MB
    ): RuntimeLoadCheckResult {
        return canLoadModel(
            snapshot = snapshotMemory(context),
            approxRamMb = approxRamMb,
            safetyMarginMb = safetyMarginMb
        )
    }

    /**
     * Pure thermal evaluation logic for deterministic unit testing and runtime checks.
     * On Android API 30+, [headroom] >= 0.85 indicates high thermal pressure (1.0 = severe throttling).
     * On API 29+, [thermalStatus] >= [PowerManager.THERMAL_STATUS_MODERATE] (2) triggers throttle.
     */
    fun evaluateThermalState(
        headroom: Float?,
        thermalStatus: Int
    ): ThermalStateSnapshot {
        val validHeadroom = headroom?.takeIf { !it.isNaN() && !it.isInfinite() && it >= 0f }
        if (validHeadroom != null && validHeadroom >= THERMAL_HEADROOM_THROTTLE_THRESHOLD) {
            return ThermalStateSnapshot(
                isThrottled = true,
                headroom = validHeadroom,
                thermalStatus = thermalStatus,
                reason = "设备温控负载偏高（thermalHeadroom=${"%.2f".format(validHeadroom)}）"
            )
        }
        if (thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE) {
            return ThermalStateSnapshot(
                isThrottled = true,
                headroom = validHeadroom,
                thermalStatus = thermalStatus,
                reason = "设备处于温控降频状态（thermalStatus=$thermalStatus）"
            )
        }
        return ThermalStateSnapshot(
            isThrottled = false,
            headroom = validHeadroom,
            thermalStatus = thermalStatus
        )
    }

    fun checkThermalState(
        context: Context,
        forecastSeconds: Int = DEFAULT_THERMAL_FORECAST_SECONDS
    ): ThermalStateSnapshot {
        val pm = runCatching {
            context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        }.getOrNull() ?: return ThermalStateSnapshot(isThrottled = false)

        val status = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { pm.currentThermalStatus }.getOrDefault(0)
        } else {
            0
        }

        val headroom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { pm.getThermalHeadroom(forecastSeconds) }
                .getOrNull()
                ?.takeIf { !it.isNaN() && !it.isInfinite() && it >= 0f }
        } else {
            null
        }

        return evaluateThermalState(headroom = headroom, thermalStatus = status)
    }
}
