package com.typefree.ime

import android.app.Application
import android.util.Log
import com.typefree.ime.bubble.TypeFreeBubbleController
import com.typefree.ime.data.AppRepository
import com.typefree.ime.ime.engine.RimeDataManager
import com.typefree.ime.ime.engine.RimeDeploymentState
import com.typefree.ime.ime.engine.RimeInputEngine
import com.typefree.ime.ime.flypy.FlypyDictionary
import com.typefree.ime.localai.LocalAiProcessInit
import com.typefree.ime.localai.LocalAiServiceClient
import com.typefree.ime.localai.LocalInferenceEngineBootstrap
import com.typefree.ime.localai.LocalModelRegistry
import com.typefree.ime.localai.ProcessUtil
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

    lateinit var localAiClient: LocalAiServiceClient
        private set

    lateinit var localModelRegistry: LocalModelRegistry
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val flypyLoading = AtomicBoolean(false)
    private val rimeDeploying = AtomicBoolean(false)
    private val _flypyDictionary = MutableStateFlow(FlypyDictionary.empty())
    val flypyDictionary: StateFlow<FlypyDictionary> = _flypyDictionary.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        instance = this
        if (ProcessUtil.isLocalAiProcess(this)) {
            LocalAiProcessInit.init(this)
            return
        }
        LocalInferenceEngineBootstrap.initialize(this)
        repository = AppRepository(this)
        localAiClient = LocalAiServiceClient(this)
        localModelRegistry = LocalModelRegistry(
            context = this,
            localAiClient = localAiClient
        )
        aiEngine = AiPipelineEngine(
            localAiClient = localAiClient,
            localModelRegistry = localModelRegistry
        )
        rimeDataManager = RimeDataManager(this)
        rimeInputEngine = RimeInputEngine(
            rimeBridge = rimeDataManager.rimeBridge,
            initialScheme = repository.settings.value.inputScheme,
            externalEnhancedSchemePredicate = { rimeDataManager.isSchemeHandledByEnhancedEngine(it) },
            customSchemaIdProvider = { rimeDataManager.resolveCustomSchemaId() },
            initialDoublePinyinLayout = repository.settings.value.doublePinyinLayout,
            onEnsureSchemaDeployed = { rimeDataManager.ensureBuiltInSchemaDeployedSync(it) }
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
                val initialSettings = repository.settings.value
                val state = rimeDataManager.deployDefaultSync(
                    initialScheme = initialSettings.inputScheme,
                    doublePinyinLayout = initialSettings.doublePinyinLayout
                )
                val latestSettings = repository.settings.value
                val latestScheme = latestSettings.inputScheme
                if (rimeDataManager.isSchemeHandledByEnhancedEngine(latestScheme)) {
                    rimeDataManager.activateEnhancedSchemeSync(latestScheme)
                }
                rimeInputEngine.setInputScheme(latestScheme, latestSettings.doublePinyinLayout)
                rimeInputEngine.setFixedCandidateOrder(latestSettings.fixedCandidateOrder)
                if (state is RimeDeploymentState.Ready) {
                    Log.i(
                        "TypeFreeRime",
                        "librime(${rimeDataManager.rimeBridge.getVersion()}) deployed in background: words=${state.wordCount}, schema=${state.activeSchemaId}, full=${state.hasFullRimeIce}, durationMs=${state.deployDurationMs}"
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
