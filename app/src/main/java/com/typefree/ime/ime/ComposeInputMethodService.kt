package com.typefree.ime.ime

import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.typefree.ime.diagnostics.ImeDiagnosticLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/**
 * Base [InputMethodService] bridging Android IME window lifecycle with Jetpack Compose's
 * required [LifecycleOwner], [ViewModelStoreOwner], and [SavedStateRegistryOwner].
 */
abstract class ComposeInputMethodService :
    InputMethodService(),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val _decorSystemBottomInsetPx = MutableStateFlow(0)
    val decorSystemBottomInsetPx: StateFlow<Int> = _decorSystemBottomInsetPx.asStateFlow()
    private val _decorBottomInsetGeometry = MutableStateFlow(ImeBottomInsetGeometry())
    val decorBottomInsetGeometry: StateFlow<ImeBottomInsetGeometry> = _decorBottomInsetGeometry.asStateFlow()
    private var attachedInsetsDecorView: View? = null
    private var attachedLayoutInputView: View? = null
    private var currentInputView: View? = null
    private var lastRawDecorBottomInsetPx: Int = 0
    private var hasObservedInsetsCallback: Boolean = false
    private var navBarColorArgb: Int = DEFAULT_LIGHT_NAV_BAR_COLOR_ARGB
    private var navBarLightIcons: Boolean = true
    private var navBarBackdropHiddenForPeek: Boolean = false
    private var navBarBackdropView: View? = null

    private val insetGeometryLayoutListener =
        View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val decorView = window?.window?.decorView ?: return@OnLayoutChangeListener
            recomputeSystemBottomInset(
                incomingCompatInsets = null,
                rawRootInsets = decorView.rootWindowInsets,
                decorView = decorView
            )
        }

    protected open fun onDecorSystemBottomInsetChanged(bottomPx: Int) {}

    protected open fun onDecorSystemBottomInsetGeometryChanged(geometry: ImeBottomInsetGeometry) {}

    private fun extractPlatformOverlayBottomPx(raw: WindowInsets?): Int {
        if (raw == null) return 0
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            raw.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.systemOverlays()
            ).bottom
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            raw.getInsets(WindowInsets.Type.systemBars()).bottom
        } else {
            0
        }
    }

    private fun resolveRawDecorBottomInsetPx(
        incomingCompatInsets: WindowInsetsCompat?,
        rawRootInsets: WindowInsets?,
        decorView: View
    ): Int {
        if (incomingCompatInsets != null) {
            val compatBottom = incomingCompatInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            ).bottom
            val platformBottom = extractPlatformOverlayBottomPx(
                incomingCompatInsets.toWindowInsets() ?: rawRootInsets
            )
            val raw = maxOf(compatBottom, platformBottom, 0)
            lastRawDecorBottomInsetPx = raw
            hasObservedInsetsCallback = true
            return raw
        }
        val compat = if (rawRootInsets != null) {
            WindowInsetsCompat.toWindowInsetsCompat(rawRootInsets, decorView)
        } else {
            ViewCompat.getRootWindowInsets(decorView)
        }
        if (compat != null || rawRootInsets != null) {
            val compatBottom = compat?.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )?.bottom ?: 0
            val platformBottom = extractPlatformOverlayBottomPx(rawRootInsets)
            val raw = maxOf(compatBottom, platformBottom, 0)
            lastRawDecorBottomInsetPx = raw
            return raw
        }
        return if (hasObservedInsetsCallback) lastRawDecorBottomInsetPx else 0
    }

    private fun computeLayoutBottomRelativeToDecor(target: View, decorView: View): Int? {
        if (!target.isAttachedToWindow || !target.isLaidOut || target.height <= 0) return null
        var top = target.top
        var current: View? = target.parent as? View
        var hops = 0
        while (current != null && current !== decorView && hops < 24) {
            top += current.top - current.scrollY
            current = current.parent as? View
            hops += 1
        }
        if (current !== decorView) return null
        return top + target.height
    }

    private fun resolveScreenBottomPx(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return 0
        return runCatching {
            val wm = getSystemService(WindowManager::class.java)
            wm?.maximumWindowMetrics?.bounds?.bottom ?: 0
        }.getOrDefault(0)
    }

    private fun recomputeSystemBottomInset(
        incomingCompatInsets: WindowInsetsCompat?,
        rawRootInsets: WindowInsets?,
        decorView: View
    ) {
        val rawBottomPx = resolveRawDecorBottomInsetPx(
            incomingCompatInsets = incomingCompatInsets,
            rawRootInsets = rawRootInsets,
            decorView = decorView
        )
        val inputView = currentInputView
        val inputBottomInDecor = if (inputView != null) {
            computeLayoutBottomRelativeToDecor(inputView, decorView)
        } else {
            null
        }
        val decorBottomOnScreenPx = if (
            decorView.isAttachedToWindow &&
            decorView.isLaidOut &&
            decorView.height > 0
        ) {
            val decorLoc = IntArray(2)
            decorView.getLocationOnScreen(decorLoc)
            ((decorLoc[1] - decorView.translationY.roundToInt()) + decorView.height).coerceAtLeast(0)
        } else {
            0
        }
        val geometry = KeyboardMetrics.resolveDecorOverlapBottomInset(
            rawDecorBottomInsetPx = rawBottomPx,
            decorHeightPx = decorView.height,
            inputBottomInWindowPx = inputBottomInDecor ?: 0,
            inputHeightPx = inputView?.height ?: 0,
            inputLaidOut = inputBottomInDecor != null,
            decorBottomOnScreenPx = decorBottomOnScreenPx,
            screenBottomPx = resolveScreenBottomPx(),
            previousEffectiveBottomInsetPx = _decorSystemBottomInsetPx.value
        )
        if (_decorBottomInsetGeometry.value != geometry) {
            val prevEffective = _decorSystemBottomInsetPx.value
            _decorBottomInsetGeometry.value = geometry
            _decorSystemBottomInsetPx.value = geometry.effectiveBottomInsetPx
            ImeDiagnosticLogger.updateRuntimeState(geometry.toDiagnosticStateMap())
            applySoftInputNavigationBarAppearance()
            onDecorSystemBottomInsetGeometryChanged(geometry)
            if (prevEffective != geometry.effectiveBottomInsetPx) {
                onDecorSystemBottomInsetChanged(geometry.effectiveBottomInsetPx)
            }
        }
    }

    protected fun syncSoftInputNavigationBarAppearance(
        backgroundColorArgb: Int,
        isLightBackground: Boolean,
        hideBackdropForPeek: Boolean = false
    ) {
        val changed = navBarColorArgb != backgroundColorArgb ||
            navBarLightIcons != isLightBackground ||
            navBarBackdropHiddenForPeek != hideBackdropForPeek
        navBarColorArgb = backgroundColorArgb
        navBarLightIcons = isLightBackground
        navBarBackdropHiddenForPeek = hideBackdropForPeek
        if (changed || navBarBackdropView == null) {
            applySoftInputNavigationBarAppearance()
        }
    }

    private fun applySoftInputNavigationBarAppearance() {
        val softWindow = window?.window ?: return
        val decorView = softWindow.decorView ?: return
        val effectiveWindowBarColor = if (navBarBackdropHiddenForPeek) {
            Color.TRANSPARENT
        } else {
            navBarColorArgb
        }
        runCatching {
            @Suppress("DEPRECATION")
            softWindow.navigationBarColor = effectiveWindowBarColor
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                softWindow.isNavigationBarContrastEnforced = false
            }
            WindowCompat.getInsetsController(softWindow, decorView).isAppearanceLightNavigationBars =
                navBarLightIcons
        }
        val backdropHeightPx = KeyboardMetrics.resolveNavBarBackdropHeightPx(
            geometry = _decorBottomInsetGeometry.value,
            isFullScreenPeekCapturing = navBarBackdropHiddenForPeek
        )
        val decorFrame = decorView as? FrameLayout ?: return
        if (backdropHeightPx <= 0) {
            navBarBackdropView?.visibility = View.GONE
            return
        }
        runCatching {
            val backdrop = navBarBackdropView?.takeIf { it.parent === decorFrame } ?: View(this).apply {
                isClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                val lp = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    backdropHeightPx,
                    Gravity.BOTTOM
                )
                decorFrame.addView(this, 0, lp)
                navBarBackdropView = this
            }
            val lp = (backdrop.layoutParams as? FrameLayout.LayoutParams)
                ?: FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    backdropHeightPx,
                    Gravity.BOTTOM
                )
            if (lp.height != backdropHeightPx || lp.gravity != Gravity.BOTTOM) {
                lp.height = backdropHeightPx
                lp.gravity = Gravity.BOTTOM
                backdrop.layoutParams = lp
            }
            backdrop.setBackgroundColor(navBarColorArgb)
            if (backdrop.visibility != View.VISIBLE) {
                backdrop.visibility = View.VISIBLE
            }
        }
    }

    protected fun installSoftInputWindowInsetsBridge() {
        val softWindow = window?.window ?: return
        applySoftInputNavigationBarAppearance()
        val decorView = softWindow.decorView ?: return
        if (attachedInsetsDecorView !== decorView) {
            attachedInsetsDecorView?.removeOnLayoutChangeListener(insetGeometryLayoutListener)
            attachedInsetsDecorView = decorView
            decorView.addOnLayoutChangeListener(insetGeometryLayoutListener)
            ViewCompat.setOnApplyWindowInsetsListener(decorView) { v, insets ->
                recomputeSystemBottomInset(
                    incomingCompatInsets = insets,
                    rawRootInsets = v.rootWindowInsets,
                    decorView = v
                )
                ViewCompat.onApplyWindowInsets(v, insets)
            }
        }
        val inputView = currentInputView
        if (attachedLayoutInputView !== inputView) {
            attachedLayoutInputView?.removeOnLayoutChangeListener(insetGeometryLayoutListener)
            attachedLayoutInputView = inputView
            inputView?.addOnLayoutChangeListener(insetGeometryLayoutListener)
        }
        recomputeSystemBottomInset(
            incomingCompatInsets = null,
            rawRootInsets = decorView.rootWindowInsets,
            decorView = decorView
        )
    }

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val viewModelStore: ViewModelStore
        get() = store

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    @Composable
    protected abstract fun KeyboardContent()

    protected fun attachViewTreeOwners(targetView: View? = null) {
        window?.window?.decorView?.let { decorView ->
            decorView.setViewTreeLifecycleOwner(this)
            decorView.setViewTreeViewModelStoreOwner(this)
            decorView.setViewTreeSavedStateRegistryOwner(this)
        }
        targetView?.let { view ->
            view.setViewTreeLifecycleOwner(this)
            view.setViewTreeViewModelStoreOwner(this)
            view.setViewTreeSavedStateRegistryOwner(this)
        }
    }

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        attachViewTreeOwners()
    }

    override fun onInitializeInterface() {
        super.onInitializeInterface()
        attachViewTreeOwners()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onEvaluateInputViewShown(): Boolean {
        super.onEvaluateInputViewShown()
        // Always show the voice keyboard UI even when an external/Bluetooth/emulator hardware keyboard is present
        return true
    }

    override fun onCreateInputView(): View {
        attachViewTreeOwners()
        val composeView = ComposeView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            attachViewTreeOwners(this)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                KeyboardContent()
            }
        }
        currentInputView = composeView
        installSoftInputWindowInsetsBridge()
        return composeView
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        attachViewTreeOwners()
        installSoftInputWindowInsetsBridge()
    }

    override fun onWindowShown() {
        super.onWindowShown()
        attachViewTreeOwners()
        installSoftInputWindowInsetsBridge()
        window?.window?.decorView?.let { decor ->
            decor.requestApplyInsets()
            decor.post {
                if (decor.isAttachedToWindow) {
                    recomputeSystemBottomInset(
                        incomingCompatInsets = null,
                        rawRootInsets = decor.rootWindowInsets,
                        decorView = decor
                    )
                }
            }
        }
        if (lifecycleRegistry.currentState != Lifecycle.State.DESTROYED &&
            lifecycleRegistry.currentState != Lifecycle.State.RESUMED
        ) {
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        }
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            lifecycleRegistry.currentState = Lifecycle.State.CREATED
        }
    }

    override fun onDestroy() {
        attachedInsetsDecorView?.removeOnLayoutChangeListener(insetGeometryLayoutListener)
        attachedLayoutInputView?.removeOnLayoutChangeListener(insetGeometryLayoutListener)
        navBarBackdropView?.let { backdrop ->
            (backdrop.parent as? ViewGroup)?.removeView(backdrop)
        }
        navBarBackdropView = null
        attachedInsetsDecorView = null
        attachedLayoutInputView = null
        currentInputView = null
        if (lifecycleRegistry.currentState != Lifecycle.State.DESTROYED) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
        store.clear()
        super.onDestroy()
    }

    companion object {
        private const val DEFAULT_LIGHT_NAV_BAR_COLOR_ARGB: Int = 0xFFF0F1F4.toInt()
    }
}
