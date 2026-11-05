package com.typefree.ime

import android.app.Application
import android.util.Log
import com.typefree.ime.bubble.TypeFreeBubbleController
import com.typefree.ime.data.AppRepository
import com.typefree.ime.ime.flypy.FlypyDictionary
import com.typefree.ime.network.AiPipelineEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class TypeFreeApp : Application() {

    lateinit var repository: AppRepository
        private set

    lateinit var aiEngine: AiPipelineEngine
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val flypyLoading = AtomicBoolean(false)
    private val _flypyDictionary = MutableStateFlow(FlypyDictionary.empty())
    val flypyDictionary: StateFlow<FlypyDictionary> = _flypyDictionary.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        instance = this
        repository = AppRepository(this)
        aiEngine = AiPipelineEngine()
        TypeFreeBubbleController.syncBubbleService(this)
        ensureFlypyDictionaryLoaded()
    }

    fun ensureFlypyDictionaryLoaded() {
        if (_flypyDictionary.value.isLoaded) return
        if (!flypyLoading.compareAndSet(false, true)) return
        appScope.launch {
            try {
                val loaded = FlypyDictionary.load(this@TypeFreeApp)
                _flypyDictionary.value = loaded
                Log.i(
                    "TypeFreeFlypy",
                    "Loaded Flypy dictionary: codes=${loaded.codeCount}, dz=${loaded.reverseCharCount}, isCustom=${FlypyDictionary.isCustomDictionaryActive(this@TypeFreeApp)}"
                )
            } catch (e: Exception) {
                Log.e("TypeFreeFlypy", "Failed to load Flypy dictionary", e)
            } finally {
                flypyLoading.set(false)
            }
        }
    }

    fun reloadFlypyDictionary() {
        appScope.launch {
            try {
                val loaded = FlypyDictionary.load(this@TypeFreeApp)
                _flypyDictionary.value = loaded
                Log.i(
                    "TypeFreeFlypy",
                    "Reloaded Flypy dictionary: codes=${loaded.codeCount}, isCustom=${FlypyDictionary.isCustomDictionaryActive(this@TypeFreeApp)}"
                )
            } catch (e: Exception) {
                Log.e("TypeFreeFlypy", "Failed to reload Flypy dictionary", e)
            }
        }
    }

    companion object {
        lateinit var instance: TypeFreeApp
            private set
    }
}
