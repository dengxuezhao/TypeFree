package com.typefree.rime

import java.io.File
import java.nio.charset.StandardCharsets

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
 * via standard UTF-8 `ByteArray` JNI in production and by lightweight test fakes in JVM unit tests.
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

    /**
     * Read-only isolated probe for Track 5.2 702-slot (`26 + 676`) dynamic `BriefPrefixIndex` construction.
     */
    fun probeCandidatesForCode(code: String, maxCount: Int = 8): List<String> {
        if (code.isEmpty()) return emptyList()
        val snap = processKeyText(code)
        clearComposition()
        return snap.candidates.take(maxCount).map { it.text }
    }

    /**
     * Single-session batch probe over all requested codes (e.g. 702 slots in one JNI crossing).
     */
    fun probeBatchCandidatesForCodes(codes: List<String>, maxCount: Int = 6): List<List<String>> {
        return codes.map { probeCandidatesForCode(it, maxCount) }
    }
}

/**
 * Thin JNI wrapper over upstream `librime` (`librime 1.17.0`) using standard UTF-8 [ByteArray]
 * transport across the JNI boundary so CJK Extension B~I 4-byte code points (e.g. `𩽾` U+29F7E)
 * never suffer JNI Modified-UTF8 surrogate corruption.
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
        val preedit = runCatching {
            String(nativeGetPreeditUtf8(), StandardCharsets.UTF_8)
        }.getOrElse {
            runCatching { nativeGetPreedit() }.getOrDefault("")
        }
        val rawInput = runCatching {
            String(nativeGetRawInputUtf8(), StandardCharsets.UTF_8)
        }.getOrElse {
            runCatching { nativeGetRawInput() }.getOrDefault("")
        }
        val rawCandidateItems: List<String> = runCatching {
            nativeGetCandidatesUtf8(limit + 1).map { bytes ->
                String(bytes, StandardCharsets.UTF_8)
            }
        }.getOrElse {
            runCatching { nativeGetCandidates(limit + 1).toList() }.getOrDefault(emptyList())
        }

        val hasMore = rawCandidateItems.size > limit
        val parsed = rawCandidateItems.take(limit).mapNotNull { item ->
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
    override fun probeCandidatesForCode(code: String, maxCount: Int): List<String> {
        if (code.isEmpty() || !nativeLibraryLoaded) return emptyList()
        val codeBytes = code.toByteArray(StandardCharsets.UTF_8)
        return runCatching {
            nativeProbeCandidatesForCodeUtf8(codeBytes, maxCount).map { bytes ->
                String(bytes, StandardCharsets.UTF_8)
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    override fun probeBatchCandidatesForCodes(codes: List<String>, maxCount: Int): List<List<String>> {
        if (codes.isEmpty() || maxCount <= 0 || !nativeLibraryLoaded) {
            return List(codes.size) { emptyList() }
        }
        val codeByteArray = Array(codes.size) { i ->
            codes[i].toByteArray(StandardCharsets.UTF_8)
        }
        return runCatching {
            val rawSlots = nativeProbeBatchCodesUtf8(codeByteArray, maxCount)
            List(codes.size) { idx ->
                val rawBytes = rawSlots.getOrNull(idx) ?: return@List emptyList()
                if (rawBytes.isEmpty()) {
                    emptyList()
                } else {
                    String(rawBytes, StandardCharsets.UTF_8)
                        .split('\u001F')
                        .filter { it.isNotEmpty() }
                }
            }
        }.getOrElse {
            codes.map { probeCandidatesForCode(it, maxCount) }
        }
    }

    @Synchronized
    override fun selectCandidate(index: Int): String? {
        if (!nativeLibraryLoaded) return null
        return runCatching {
            nativeSelectCandidateUtf8(index)?.let { String(it, StandardCharsets.UTF_8) }
        }.getOrElse {
            runCatching { nativeSelectCandidate(index) }.getOrNull()
        }
    }

    @Synchronized
    override fun commitComposition(): String? {
        if (!nativeLibraryLoaded) return null
        return runCatching {
            nativeCommitCompositionUtf8()?.let { String(it, StandardCharsets.UTF_8) }
        }.getOrElse {
            runCatching { nativeCommitComposition() }.getOrNull()
        }
    }

    @Synchronized
    override fun getCommit(): String? {
        if (!nativeLibraryLoaded) return null
        return runCatching {
            nativeGetCommitUtf8()?.let { String(it, StandardCharsets.UTF_8) }
        }.getOrElse {
            runCatching { nativeGetCommit() }.getOrNull()
        }
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
        val dictFiles = sharedDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".dict.yaml") }
            .toList()
        for (file in dictFiles) {
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
    private external fun nativeGetPreeditUtf8(): ByteArray
    private external fun nativeGetRawInput(): String
    private external fun nativeGetRawInputUtf8(): ByteArray
    private external fun nativeGetCandidates(maxCount: Int): Array<String>
    private external fun nativeGetCandidatesUtf8(maxCount: Int): Array<ByteArray>
    private external fun nativeProbeCandidatesForCodeUtf8(codeUtf8: ByteArray, maxCount: Int): Array<ByteArray>
    private external fun nativeProbeBatchCodesUtf8(codesUtf8: Array<ByteArray>, maxCount: Int): Array<ByteArray>
    private external fun nativeSelectCandidate(index: Int): String?
    private external fun nativeSelectCandidateUtf8(index: Int): ByteArray?
    private external fun nativeCommitComposition(): String?
    private external fun nativeCommitCompositionUtf8(): ByteArray?
    private external fun nativeGetCommit(): String?
    private external fun nativeGetCommitUtf8(): ByteArray?
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
                loadHostMacOsNativeLibraries()
            } catch (_: Throwable) {
                false
            }
        }

        private fun loadHostMacOsNativeLibraries(): Boolean {
            val candidates = listOfNotNull(
                System.getProperty("typefree.rime.native.dir")?.takeIf { it.isNotBlank() }?.let { File(it) },
                File("rime/build/host-macos"),
                File("../rime/build/host-macos")
            )
            for (dir in candidates) {
                val librime = File(dir, "librime.dylib")
                val libjni = File(dir, "librime_jni.dylib")
                if (librime.isFile && libjni.isFile) {
                    val loaded = runCatching {
                        System.load(librime.canonicalPath)
                        System.load(libjni.canonicalPath)
                        true
                    }.getOrDefault(false)
                    if (loaded) return true
                }
            }
            return false
        }
    }
}
