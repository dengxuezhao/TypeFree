package com.typefree.ime

import android.app.Application
import android.util.Log
import com.typefree.ime.bubble.TypeFreeBubbleController
import com.typefree.ime.data.AppRepository
import com.typefree.ime.ime.engine.RimeDataManager
import com.typefree.ime.ime.engine.RimeDeploymentState
import com.typefree.ime.ime.engine.RimeInputEngine
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

    lateinit var rimeDataManager: RimeDataManager
        private set

    lateinit var rimeInputEngine: RimeInputEngine
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val flypyLoading = AtomicBoolean(false)
    private val rimeDeploying = AtomicBoolean(false)
    private val _flypyDictionary = MutableStateFlow(FlypyDictionary.empty())
    val flypyDictionary: StateFlow<FlypyDictionary> = _flypyDictionary.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        instance = this
        repository = AppRepository(this)
        aiEngine = AiPipelineEngine()
        rimeDataManager = RimeDataManager(this)
        rimeInputEngine = RimeInputEngine(
            rimeBridge = rimeDataManager.rimeBridge,
            initialScheme = repository.settings.value.inputScheme
        )
        rimeInputEngine.setFixedCandidateOrder(repository.settings.value.fixedCandidateOrder)
        TypeFreeBubbleController.syncBubbleService(this)
        ensureFlypyDictionaryLoaded()
        ensureRimeDeployed()
    }

    fun ensureRimeDeployed() {
        if (rimeDataManager.isReady) return
        if (!rimeDeploying.compareAndSet(false, true)) return
        appScope.launch {
            try {
                val initialScheme = repository.settings.value.inputScheme
                val state = rimeDataManager.deployDefaultSync(initialScheme)
                rimeInputEngine.setInputScheme(initialScheme)
                rimeInputEngine.setFixedCandidateOrder(repository.settings.value.fixedCandidateOrder)
                if (state is RimeDeploymentState.Ready) {
                    Log.i(
                        "TypeFreeRime",
                        "Rime deployed in background: words=${state.wordCount}, schema=${state.activeSchemaId}, full=${state.hasFullRimeIce}"
                    )
                }
            } catch (e: Exception) {
                Log.e("TypeFreeRime", "Failed to deploy Rime in background", e)
            } finally {
                rimeDeploying.set(false)
            }
        }
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
