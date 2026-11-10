package com.typefree.rime

import java.io.File

data class RimeCandidate(
    val text: String,
    val comment: String = ""
)

data class RimeContextSnapshot(
    val preedit: String = "",
    val rawInput: String = "",
    val candidates: List<RimeCandidate> = emptyList(),
    val pageNo: Int = 0,
    val isLastPage: Boolean = true,
    val hasMore: Boolean = false
)

/**
 * Thin Kotlin interface over `librime` C API (`rime_api.h`), implemented by [RimeBridge]
 * via JNI in production and by lightweight test fakes/stubs in JVM unit tests.
 */
interface RimeSessionClient {
    val activeSchemaId: String
    var pageSize: Int
    val isNativeLoaded: Boolean
    val isDeployed: Boolean
    val wordCount: Int

    fun initialize(sharedDir: String, userDir: String): Boolean
    fun deploy(schemaId: String = activeSchemaId): Boolean
    fun selectSchema(schemaId: String): Boolean
    fun processKey(keyCode: Int, mask: Int = 0): Boolean
    fun processKeyText(keys: String): RimeContextSnapshot
    fun getContext(maxCandidates: Int = pageSize): RimeContextSnapshot
    fun selectCandidate(index: Int): String?
    fun commitComposition(): String?
    fun getCommit(): String?
    fun clearComposition()
    fun setOption(option: String, value: Boolean)
    fun getOption(option: String): Boolean
    fun syncUserData(): Boolean
    fun getVersion(): String
}

/**
 * Thin JNI wrapper over upstream `librime` (`librime.so` + `librime-lua.so` +
 * `librime-octagram.so` + `librime-predict.so` + `librime_jni.so`).
 *
 * Contains NO custom fallback input engine; all syllable segmentation, speller algebra,
 * candidate ranking, userdb (`*.userdb` LevelDB) dynamic frequency learning, and phrase
 * composition are executed natively by upstream `librime` (`rime_get_api()`).
 */
class RimeBridge : RimeSessionClient {

    override var activeSchemaId: String = "rime_ice"
        private set

    override var pageSize: Int = 24

    override val isNativeLoaded: Boolean
        get() = nativeLibraryLoaded

    @Volatile
    override var isDeployed: Boolean = false
        private set

    @Volatile
    override var wordCount: Int = 0
        private set

    private var currentSharedDir: String = ""
    private var currentUserDir: String = ""

    @Synchronized
    override fun initialize(sharedDir: String, userDir: String): Boolean {
        File(sharedDir).mkdirs()
        File(userDir).mkdirs()
        currentSharedDir = sharedDir
        currentUserDir = userDir
        if (!nativeLibraryLoaded) return false
        return runCatching { nativeInitialize(sharedDir, userDir) }.getOrDefault(false)
    }

    @Synchronized
    override fun deploy(schemaId: String): Boolean {
        if (schemaId.isNotBlank()) {
            activeSchemaId = schemaId
        }
        if (!nativeLibraryLoaded) return false
        val ok = runCatching { nativeDeploy(activeSchemaId) }.getOrDefault(false)
        if (ok) {
            isDeployed = true
            wordCount = countDictionaryEntries(File(currentSharedDir))
        }
        return ok
    }

    @Synchronized
    override fun selectSchema(schemaId: String): Boolean {
        if (schemaId.isBlank()) return false
        activeSchemaId = schemaId
        if (!nativeLibraryLoaded) return false
        return runCatching { nativeSelectSchema(schemaId) }.getOrDefault(false)
    }

    @Synchronized
    override fun processKey(keyCode: Int, mask: Int): Boolean {
        if (!nativeLibraryLoaded) return false
        return runCatching { nativeProcessKey(keyCode, mask) }.getOrDefault(false)
    }

    @Synchronized
    override fun processKeyText(keys: String): RimeContextSnapshot {
        if (!nativeLibraryLoaded) return RimeContextSnapshot()
        runCatching { nativeProcessKeyText(keys) }
        return getContext(pageSize)
    }

    @Synchronized
    override fun getContext(maxCandidates: Int): RimeContextSnapshot {
        if (!nativeLibraryLoaded) return RimeContextSnapshot()
        val limit = maxCandidates.coerceAtLeast(1)
        val preedit = runCatching { nativeGetPreedit() }.getOrDefault("")
        val rawInput = runCatching { nativeGetRawInput() }.getOrDefault("")
        val rawCandidates = runCatching { nativeGetCandidates(limit + 1) }.getOrDefault(emptyArray())
        val hasMore = rawCandidates.size > limit
        val parsed = rawCandidates.take(limit).mapNotNull { item ->
            val tabIdx = item.indexOf('\t')
            if (tabIdx < 0) {
                if (item.isNotEmpty()) RimeCandidate(item) else null
            } else {
                val text = item.substring(0, tabIdx)
                val comment = item.substring(tabIdx + 1)
                if (text.isNotEmpty()) RimeCandidate(text, comment) else null
            }
        }
        return RimeContextSnapshot(
            preedit = preedit,
            rawInput = rawInput,
            candidates = parsed,
            pageNo = 0,
            isLastPage = !hasMore,
            hasMore = hasMore
        )
    }

    @Synchronized
    override fun selectCandidate(index: Int): String? {
        if (!nativeLibraryLoaded) return null
        return runCatching { nativeSelectCandidate(index) }.getOrNull()
    }

    @Synchronized
    override fun commitComposition(): String? {
        if (!nativeLibraryLoaded) return null
        return runCatching { nativeCommitComposition() }.getOrNull()
    }

    @Synchronized
    override fun getCommit(): String? {
        if (!nativeLibraryLoaded) return null
        return runCatching { nativeGetCommit() }.getOrNull()
    }

    @Synchronized
    override fun clearComposition() {
        if (!nativeLibraryLoaded) return
        runCatching { nativeClearComposition() }
    }

    @Synchronized
    override fun setOption(option: String, value: Boolean) {
        if (!nativeLibraryLoaded) return
        runCatching { nativeSetOption(option, value) }
    }

    @Synchronized
    override fun getOption(option: String): Boolean {
        if (!nativeLibraryLoaded) return false
        return runCatching { nativeGetOption(option) }.getOrDefault(false)
    }

    @Synchronized
    override fun syncUserData(): Boolean {
        if (!nativeLibraryLoaded) return false
        return runCatching { nativeSyncUserData() }.getOrDefault(false)
    }

    override fun getVersion(): String {
        if (!nativeLibraryLoaded) return "unavailable"
        return runCatching { nativeGetVersion() }.getOrDefault("unknown")
    }

    private fun countDictionaryEntries(sharedDir: File): Int {
        if (!sharedDir.exists()) return 0
        var count = 0
        val dictFiles = listOf(
            File(sharedDir, "cn_dicts/8105.dict.yaml"),
            File(sharedDir, "cn_dicts/base.dict.yaml")
        )
        for (file in dictFiles) {
            if (!file.exists()) continue
            var inBody = false
            runCatching {
                file.forEachLine(Charsets.UTF_8) { line ->
                    val trimmed = line.trim()
                    if (trimmed == "...") {
                        inBody = true
                    } else if (inBody && trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                        count++
                    }
                }
            }
        }
        return count
    }

    private external fun nativeInitialize(sharedDir: String, userDir: String): Boolean
    private external fun nativeDeploy(schemaId: String): Boolean
    private external fun nativeSelectSchema(schemaId: String): Boolean
    private external fun nativeProcessKey(keyCode: Int, mask: Int): Boolean
    private external fun nativeProcessKeyText(keys: String): Boolean
    private external fun nativeGetPreedit(): String
    private external fun nativeGetRawInput(): String
    private external fun nativeGetCandidates(maxCount: Int): Array<String>
    private external fun nativeSelectCandidate(index: Int): String?
    private external fun nativeCommitComposition(): String?
    private external fun nativeGetCommit(): String?
    private external fun nativeClearComposition()
    private external fun nativeSetOption(option: String, value: Boolean)
    private external fun nativeGetOption(option: String): Boolean
    private external fun nativeSyncUserData(): Boolean
    private external fun nativeGetVersion(): String

    companion object {
        const val XK_BackSpace = 0xff08
        const val KEY_BACKSPACE = XK_BackSpace
        const val XK_Return = 0xff0d
        const val XK_Escape = 0xff1b
        const val XK_space = 0x0020

        @Volatile
        private var nativeLibraryLoaded: Boolean = false

        init {
            nativeLibraryLoaded = try {
                System.loadLibrary("rime")
                System.loadLibrary("rime_jni")
                true
            } catch (_: UnsatisfiedLinkError) {
                false
            } catch (_: Throwable) {
                false
            }
        }
    }
}
