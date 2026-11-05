package com.typefree.ime.ime

import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
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
    }

    override fun onWindowShown() {
        super.onWindowShown()
        attachViewTreeOwners()
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
