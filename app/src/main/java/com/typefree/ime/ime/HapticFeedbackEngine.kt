package com.typefree.ime.ime

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.staticCompositionLocalOf
import com.typefree.ime.data.AppSettings
import com.typefree.ime.data.HapticPreset
import kotlin.math.roundToInt

/**
 * Tiered tactile feedback categories modeled after flagship linear-motor IMEs:
 * - [ALPHA_KEY]: Lightest crisp impulse on finger DOWN to prevent fatigue during rapid typing.
 * - [FUNCTION_KEY]: Slightly firmer anchor feel for Space / Enter / Backspace / Shift / 123.
 * - [CURSOR_TICK]: Ultra-short ratchet tick for spacebar cursor slide steps and repeating backspace.
 * - [GESTURE_CONFIRM]: Distinct tactile confirmation when swipe-up / long-press / undo threshold is crossed.
 * - [CANDIDATE_TAP]: Gentle impulse when selecting candidates, predictions, or emojis.
 * - [MIC_STRONG]: Prominent cue for starting/stopping voice capture or surfacing errors.
 */
enum class HapticTier(
    val scaleMultiplier: Float,
    val isStrongFallback: Boolean,
    val soundType: KeySoundType
) {
    ALPHA_KEY(0.85f, false, KeySoundType.STANDARD),
    FUNCTION_KEY(1.15f, false, KeySoundType.STANDARD),
    CURSOR_TICK(0.55f, false, KeySoundType.NONE),
    GESTURE_CONFIRM(1.30f, true, KeySoundType.STANDARD),
    CANDIDATE_TAP(0.65f, false, KeySoundType.STANDARD),
    MIC_STRONG(1.40f, true, KeySoundType.NONE)
}

enum class KeySoundType {
    NONE,
    STANDARD,
    SPACEBAR,
    DELETE,
    RETURN
}

enum class HardwarePrimitiveKind {
    LOW_TICK,
    TICK,
    CLICK
}

enum class SystemHapticConstantKind {
    KEYBOARD_PRESS,
    CLOCK_TICK,
    CONFIRM_LONG_PRESS
}

sealed interface HapticPulsePlan {
    data object None : HapticPulsePlan

    data class SystemViewFeedback(
        val constantKind: SystemHapticConstantKind,
        val fallbackPlan: HapticPulsePlan
    ) : HapticPulsePlan

    data class PrimitiveComposition(
        val primitive: HardwarePrimitiveKind,
        val scale: Float
    ) : HapticPulsePlan

    data class ShortOneShot(
        val durationMs: Long,
        val amplitude: Int // 1..255, or DEFAULT_AMPLITUDE (-1) when hardware lacks amplitude control
    ) : HapticPulsePlan
}

data class HapticHardwareCaps(
    val sdkInt: Int,
    val hasVibrator: Boolean,
    val hasAmplitudeControl: Boolean,
    val supportsLowTick: Boolean,
    val supportsTick: Boolean,
    val supportsClick: Boolean
)

/**
 * Pure-Kotlin haptic waveform planner (100% unit-testable on JVM without Android framework).
 */
object HapticFeedbackPlanner {

    const val MIN_STRENGTH = 10
    const val MAX_STRENGTH = 100
    const val DEFAULT_STRENGTH = 38
    const val MIN_SOUND_VOLUME = 5
    const val MAX_SOUND_VOLUME = 100
    const val DEFAULT_SOUND_VOLUME = 50

    fun resolveEffectivePreset(settings: AppSettings): HapticPreset {
        if (!settings.hapticFeedback || settings.hapticPreset == HapticPreset.OFF) {
            return HapticPreset.OFF
        }
        return settings.hapticPreset
    }

    fun resolveEffectiveStrength(preset: HapticPreset, customStrength: Int): Int {
        return when (preset) {
            HapticPreset.OFF -> 0
            HapticPreset.SYSTEM -> DEFAULT_STRENGTH
            HapticPreset.SOFT -> HapticPreset.SOFT.defaultStrength
            HapticPreset.CRISP -> HapticPreset.CRISP.defaultStrength
            HapticPreset.STRONG -> HapticPreset.STRONG.defaultStrength
            HapticPreset.CUSTOM -> customStrength.coerceIn(MIN_STRENGTH, MAX_STRENGTH)
        }
    }

    fun resolveSoundVolumeFloat(volumePercent: Int): Float {
        return (volumePercent.coerceIn(MIN_SOUND_VOLUME, MAX_SOUND_VOLUME) / 100f)
    }

    fun resolvePulsePlan(
        preset: HapticPreset,
        strengthPercent: Int,
        tier: HapticTier,
        caps: HapticHardwareCaps,
        hasAttachedView: Boolean
    ): HapticPulsePlan {
        if (preset == HapticPreset.OFF || !caps.hasVibrator) {
            return HapticPulsePlan.None
        }

        val resolvedStrength = resolveEffectiveStrength(preset, strengthPercent)
        val hardwarePlan = resolveDirectHardwarePlan(
            strengthPercent = resolvedStrength,
            tier = tier,
            caps = caps
        )

        if (preset == HapticPreset.SYSTEM && hasAttachedView) {
            val constantKind = when (tier) {
                HapticTier.ALPHA_KEY,
                HapticTier.FUNCTION_KEY,
                HapticTier.CANDIDATE_TAP -> SystemHapticConstantKind.KEYBOARD_PRESS
                HapticTier.CURSOR_TICK -> SystemHapticConstantKind.CLOCK_TICK
                HapticTier.GESTURE_CONFIRM,
                HapticTier.MIC_STRONG -> SystemHapticConstantKind.CONFIRM_LONG_PRESS
            }
            return HapticPulsePlan.SystemViewFeedback(
                constantKind = constantKind,
                fallbackPlan = hardwarePlan
            )
        }

        return hardwarePlan
    }

    private fun resolveDirectHardwarePlan(
        strengthPercent: Int,
        tier: HapticTier,
        caps: HapticHardwareCaps
    ): HapticPulsePlan {
        val baseFraction = (strengthPercent.coerceIn(MIN_STRENGTH, MAX_STRENGTH) / 100f)
        val scaled = (baseFraction * tier.scaleMultiplier).coerceIn(0.08f, 1.0f)

        // Tier 1 (Android 11+ LRA): active-braked hardware primitives with continuous scaling
        if (caps.sdkInt >= Build.VERSION_CODES.R) {
            val primitive: HardwarePrimitiveKind? = when (tier) {
                HapticTier.CURSOR_TICK -> when {
                    caps.supportsTick -> HardwarePrimitiveKind.TICK
                    caps.supportsLowTick -> HardwarePrimitiveKind.LOW_TICK
                    caps.supportsClick -> HardwarePrimitiveKind.CLICK
                    else -> null
                }
                HapticTier.ALPHA_KEY,
                HapticTier.CANDIDATE_TAP -> when {
                    caps.supportsLowTick -> HardwarePrimitiveKind.LOW_TICK
                    caps.supportsClick -> HardwarePrimitiveKind.CLICK
                    caps.supportsTick -> HardwarePrimitiveKind.TICK
                    else -> null
                }
                HapticTier.FUNCTION_KEY,
                HapticTier.GESTURE_CONFIRM,
                HapticTier.MIC_STRONG -> when {
                    caps.supportsClick -> HardwarePrimitiveKind.CLICK
                    caps.supportsLowTick -> HardwarePrimitiveKind.LOW_TICK
                    caps.supportsTick -> HardwarePrimitiveKind.TICK
                    else -> null
                }
            }
            if (primitive != null) {
                return HapticPulsePlan.PrimitiveComposition(
                    primitive = primitive,
                    scale = scaled
                )
            }
        }

        // Tier 2 / 3 (Legacy ERM or LRA without primitive API): short crisp pulse (4..12ms)
        val durationMs = when (tier) {
            HapticTier.CURSOR_TICK -> (4L + (scaled * 3f).roundToInt()).coerceIn(4L, 7L)
            HapticTier.ALPHA_KEY,
            HapticTier.CANDIDATE_TAP -> (5L + (scaled * 4f).roundToInt()).coerceIn(5L, 9L)
            HapticTier.FUNCTION_KEY -> (6L + (scaled * 4f).roundToInt()).coerceIn(6L, 10L)
            HapticTier.GESTURE_CONFIRM,
            HapticTier.MIC_STRONG -> (8L + (scaled * 5f).roundToInt()).coerceIn(8L, 13L)
        }
        val amplitude = if (caps.hasAmplitudeControl) {
            (28 + (scaled * 227f).roundToInt()).coerceIn(28, 255)
        } else {
            VibrationEffect.DEFAULT_AMPLITUDE
        }
        return HapticPulsePlan.ShortOneShot(
            durationMs = durationMs,
            amplitude = amplitude
        )
    }
}

/**
 * Multi-touch-safe touch-down feedback deduplicator.
 *
 * Every physical finger-DOWN fires immediately at 0ms and enqueues a short-lived ack token.
 * When finger-UP commits via [TypingController], [consumeMatchingUpAck] swallows the duplicate
 * non-strong `performKeyFeedback` call while allowing programmatic / hardware-key / unit-test calls
 * (where no finger-DOWN occurred) to still trigger feedback normally.
 */
class HapticTouchDeduper(
    private val clockMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val ackTtlMs: Long = DEFAULT_ACK_TTL_MS
) {
    private val lock = Any()
    private val pendingDownTimestamps = ArrayDeque<Long>()

    fun recordTouchDownFeedback() = synchronized(lock) {
        val now = clockMs()
        pruneExpiredLocked(now)
        pendingDownTimestamps.addLast(now)
        while (pendingDownTimestamps.size > MAX_PENDING_ACKS) {
            pendingDownTimestamps.removeFirst()
        }
    }

    /**
     * Consumes one pending touch-down ack if a touch already played feedback on DOWN.
     * Returns `true` if the follow-up UP feedback should be skipped as a duplicate.
     */
    fun consumeMatchingUpAck(): Boolean = synchronized(lock) {
        val now = clockMs()
        pruneExpiredLocked(now)
        if (pendingDownTimestamps.isEmpty()) {
            false
        } else {
            pendingDownTimestamps.removeFirst()
            true
        }
    }

    fun clear() = synchronized(lock) {
        pendingDownTimestamps.clear()
    }

    private fun pruneExpiredLocked(now: Long) {
        while (pendingDownTimestamps.isNotEmpty() && (now - pendingDownTimestamps.first()) > ackTtlMs) {
            pendingDownTimestamps.removeFirst()
        }
    }

    companion object {
        const val DEFAULT_ACK_TTL_MS = 450L
        private const val MAX_PENDING_ACKS = 8
    }
}

/**
 * Callback interface provided via [LocalKeyFeedbackTrigger] to all keyboard key composables so
 * touch-DOWN feedback fires at 0ms with zero per-key IPC lookup overhead.
 */
fun interface KeyFeedbackTrigger {
    fun onTouchFeedback(
        tier: HapticTier,
        view: View?,
        soundType: KeySoundType,
        markAsTouchDown: Boolean
    )
}

val LocalKeyFeedbackTrigger = staticCompositionLocalOf<KeyFeedbackTrigger> {
    KeyFeedbackTrigger { _, _, _, _ -> }
}

/**
 * Cached Android haptic & key-sound runtime controller.
 * Caches [Vibrator], [AudioManager], and hardware primitive support flags once per lifecycle.
 */
class KeyFeedbackController(
    context: Context,
    private val settingsProvider: () -> AppSettings
) {
    private val vibrator: Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }.getOrNull()

    private val audioManager: AudioManager? = runCatching {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }.getOrNull()

    val hardwareCaps: HapticHardwareCaps = probeHardwareCaps(vibrator)

    val touchDeduper = HapticTouchDeduper()

    val composeTrigger: KeyFeedbackTrigger = KeyFeedbackTrigger { tier, view, soundType, markAsTouchDown ->
        if (markAsTouchDown) {
            touchDeduper.recordTouchDownFeedback()
        }
        performTieredHaptic(tier = tier, view = view)
        if (soundType != KeySoundType.NONE) {
            playKeySound(soundType)
        }
    }

    fun performTieredHaptic(
        tier: HapticTier,
        view: View? = null,
        settingsOverride: AppSettings? = null
    ) {
        val settings = settingsOverride ?: settingsProvider()
        val preset = HapticFeedbackPlanner.resolveEffectivePreset(settings)
        val plan = HapticFeedbackPlanner.resolvePulsePlan(
            preset = preset,
            strengthPercent = settings.hapticStrength,
            tier = tier,
            caps = hardwareCaps,
            hasAttachedView = view != null
        )
        executePlan(plan = plan, view = view)
    }

    fun playKeySound(
        soundType: KeySoundType = KeySoundType.STANDARD,
        settingsOverride: AppSettings? = null
    ) {
        if (soundType == KeySoundType.NONE) return
        val settings = settingsOverride ?: settingsProvider()
        if (!settings.interactionSounds) return
        val am = audioManager ?: return
        val effect = when (soundType) {
            KeySoundType.STANDARD -> AudioManager.FX_KEYPRESS_STANDARD
            KeySoundType.SPACEBAR -> AudioManager.FX_KEYPRESS_SPACEBAR
            KeySoundType.DELETE -> AudioManager.FX_KEYPRESS_DELETE
            KeySoundType.RETURN -> AudioManager.FX_KEYPRESS_RETURN
            KeySoundType.NONE -> return
        }
        val volume = HapticFeedbackPlanner.resolveSoundVolumeFloat(settings.keySoundVolume)
        runCatching { am.playSoundEffect(effect, volume) }
    }

    /**
     * Triggered from [TypeFreeInputMethodService.performKeyFeedback] (UP/programmatic path).
     * Automatically skips duplicate non-strong feedback when [composeTrigger] already fired on DOWN.
     */
    fun performLegacyUpOrProgrammaticFeedback(
        strong: Boolean,
        playSound: Boolean = true,
        view: View? = null
    ) {
        if (!strong && touchDeduper.consumeMatchingUpAck()) {
            return
        }
        val tier = if (strong) HapticTier.GESTURE_CONFIRM else HapticTier.ALPHA_KEY
        performTieredHaptic(tier = tier, view = view)
        if (playSound) {
            playKeySound(KeySoundType.STANDARD)
        }
    }

    /**
     * Live preview trigger for the Settings UI slider & preset chips (always bypasses dedupe).
     */
    fun previewInSettings(
        preset: HapticPreset,
        strengthPercent: Int,
        tier: HapticTier = HapticTier.ALPHA_KEY,
        view: View? = null
    ) {
        val plan = HapticFeedbackPlanner.resolvePulsePlan(
            preset = preset,
            strengthPercent = strengthPercent,
            tier = tier,
            caps = hardwareCaps,
            hasAttachedView = view != null
        )
        executePlan(plan = plan, view = view)
    }

    fun previewSoundInSettings(volumePercent: Int) {
        val am = audioManager ?: return
        val volume = HapticFeedbackPlanner.resolveSoundVolumeFloat(volumePercent)
        runCatching { am.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD, volume) }
    }

    private fun executePlan(plan: HapticPulsePlan, view: View?) {
        when (plan) {
            HapticPulsePlan.None -> Unit
            is HapticPulsePlan.SystemViewFeedback -> {
                val handled = if (view != null) {
                    val constant = resolveAndroidHapticConstant(plan.constantKind)
                    runCatching {
                        view.performHapticFeedback(
                            constant,
                            HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
                        )
                    }.getOrDefault(false)
                } else {
                    false
                }
                if (!handled) {
                    executePlan(plan.fallbackPlan, view = null)
                }
            }
            is HapticPulsePlan.PrimitiveComposition -> {
                val vib = vibrator ?: return
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val primId = when (plan.primitive) {
                        HardwarePrimitiveKind.LOW_TICK -> {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                VibrationEffect.Composition.PRIMITIVE_LOW_TICK
                            } else {
                                VibrationEffect.Composition.PRIMITIVE_CLICK
                            }
                        }
                        HardwarePrimitiveKind.TICK -> VibrationEffect.Composition.PRIMITIVE_TICK
                        HardwarePrimitiveKind.CLICK -> VibrationEffect.Composition.PRIMITIVE_CLICK
                    }
                    runCatching {
                        val effect = VibrationEffect.startComposition()
                            .addPrimitive(primId, plan.scale.coerceIn(0.05f, 1.0f))
                            .compose()
                        vibrateWithTouchAttributes(vib, effect)
                    }
                }
            }
            is HapticPulsePlan.ShortOneShot -> {
                val vib = vibrator ?: return
                runCatching {
                    val effect = VibrationEffect.createOneShot(plan.durationMs, plan.amplitude)
                    vibrateWithTouchAttributes(vib, effect)
                }
            }
        }
    }

    private fun vibrateWithTouchAttributes(vib: Vibrator, effect: VibrationEffect) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val attrs = VibrationAttributes.Builder()
                .setUsage(VibrationAttributes.USAGE_TOUCH)
                .build()
            vib.vibrate(effect, attrs)
        } else {
            vib.vibrate(effect)
        }
    }

    private fun resolveAndroidHapticConstant(kind: SystemHapticConstantKind): Int {
        return when (kind) {
            SystemHapticConstantKind.KEYBOARD_PRESS -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    HapticFeedbackConstants.KEYBOARD_PRESS
                } else {
                    HapticFeedbackConstants.KEYBOARD_TAP
                }
            }
            SystemHapticConstantKind.CLOCK_TICK -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    HapticFeedbackConstants.TEXT_HANDLE_MOVE
                } else {
                    HapticFeedbackConstants.CLOCK_TICK
                }
            }
            SystemHapticConstantKind.CONFIRM_LONG_PRESS -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    HapticFeedbackConstants.CONFIRM
                } else {
                    HapticFeedbackConstants.LONG_PRESS
                }
            }
        }
    }

    companion object {
        private fun probeHardwareCaps(vibrator: Vibrator?): HapticHardwareCaps {
            val hasVib = runCatching { vibrator?.hasVibrator() == true }.getOrDefault(false)
            if (!hasVib || vibrator == null) {
                return HapticHardwareCaps(
                    sdkInt = Build.VERSION.SDK_INT,
                    hasVibrator = false,
                    hasAmplitudeControl = false,
                    supportsLowTick = false,
                    supportsTick = false,
                    supportsClick = false
                )
            }
            val hasAmp = runCatching { vibrator.hasAmplitudeControl() }.getOrDefault(false)
            var lowTick = false
            var tick = false
            var click = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                click = runCatching {
                    vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK)
                }.getOrDefault(false)
                tick = runCatching {
                    vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_TICK)
                }.getOrDefault(false)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    lowTick = runCatching {
                        vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_LOW_TICK)
                    }.getOrDefault(false)
                }
            }
            return HapticHardwareCaps(
                sdkInt = Build.VERSION.SDK_INT,
                hasVibrator = true,
                hasAmplitudeControl = hasAmp,
                supportsLowTick = lowTick,
                supportsTick = tick,
                supportsClick = click
            )
        }
    }
}
