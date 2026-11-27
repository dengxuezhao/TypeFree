package com.typefree.ime.ime

import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.ViewCompat
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
    private var attachedInsetsDecorView: View? = null

    protected open fun onDecorSystemBottomInsetChanged(bottomPx: Int) {}

    private fun updateSystemBottomInsetFromWindow(rawRootInsets: WindowInsets?, hostView: View) {
        val compat = if (rawRootInsets != null) {
            WindowInsetsCompat.toWindowInsetsCompat(rawRootInsets, hostView)
        } else {
            ViewCompat.getRootWindowInsets(hostView)
        }
        val compatBottom = compat?.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )?.bottom ?: 0
        val platformOverlayBottom = if (
            rawRootInsets != null &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        ) {
            rawRootInsets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.systemOverlays()
            ).bottom
        } else if (
            rawRootInsets != null &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        ) {
            rawRootInsets.getInsets(WindowInsets.Type.systemBars()).bottom
        } else {
            0
        }
        val resolvedPx = maxOf(compatBottom, platformOverlayBottom, 0)
        if (_decorSystemBottomInsetPx.value != resolvedPx) {
            _decorSystemBottomInsetPx.value = resolvedPx
            onDecorSystemBottomInsetChanged(resolvedPx)
        }
    }

    protected fun installSoftInputWindowInsetsBridge() {
        val softWindow = window?.window ?: return
        runCatching {
            softWindow.navigationBarColor = Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                softWindow.isNavigationBarContrastEnforced = false
            }
        }
        val decorView = softWindow.decorView ?: return
        if (attachedInsetsDecorView !== decorView) {
            attachedInsetsDecorView = decorView
            ViewCompat.setOnApplyWindowInsetsListener(decorView) { v, insets ->
                updateSystemBottomInsetFromWindow(v.rootWindowInsets, v)
                val barBottom = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
                ).bottom.coerceAtLeast(0)
                if (barBottom > _decorSystemBottomInsetPx.value) {
                    _decorSystemBottomInsetPx.value = barBottom
                    onDecorSystemBottomInsetChanged(barBottom)
                }
                ViewCompat.onApplyWindowInsets(v, insets)
            }
        }
        updateSystemBottomInsetFromWindow(decorView.rootWindowInsets, decorView)
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
        installSoftInputWindowInsetsBridge()
        return ComposeView(this).apply {
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
        window?.window?.decorView?.requestApplyInsets()
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
        if (lifecycleRegistry.currentState != Lifecycle.State.DESTROYED) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
        store.clear()
        super.onDestroy()
    }
}
