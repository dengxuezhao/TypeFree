// SPDX-FileCopyrightText: 2015 - 2026 Rime community / TypeFree v3.6
// SPDX-License-Identifier: GPL-3.0-or-later

#include <jni.h>
#include <rime_api.h>

#include <cstdint>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <mutex>
#include <sstream>
#include <string>
#include <vector>

#ifdef __ANDROID__
#include <android/log.h>
#define LOG_TAG "TypeFreeRimeJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#else
#include <cstdio>
#define LOGI(...)                                                       \
  do {                                                                  \
    std::fprintf(stderr, "[I/TypeFreeRimeJNI] ");                       \
    std::fprintf(stderr, __VA_ARGS__);                                  \
    std::fprintf(stderr, "\n");                                         \
  } while (0)
#define LOGW(...)                                                       \
  do {                                                                  \
    std::fprintf(stderr, "[W/TypeFreeRimeJNI] ");                       \
    std::fprintf(stderr, __VA_ARGS__);                                  \
    std::fprintf(stderr, "\n");                                         \
  } while (0)
#define LOGE(...)                                                       \
  do {                                                                  \
    std::fprintf(stderr, "[E/TypeFreeRimeJNI] ");                       \
    std::fprintf(stderr, __VA_ARGS__);                                  \
    std::fprintf(stderr, "\n");                                         \
  } while (0)
#endif

extern void rime_require_module_lua();
extern void rime_require_module_octagram();
extern void rime_require_module_predict();

static void declare_librime_module_dependencies() {
  rime_require_module_lua();
  rime_require_module_octagram();
  rime_require_module_predict();
}

namespace {

std::mutex g_mutex;
bool g_setup_done = false;
bool g_initialized = false;
RimeSessionId g_session_id = 0;
RimeSessionId g_probe_session_id = 0;
std::string g_shared_dir;
std::string g_user_dir;
std::string g_active_schema = "rime_ice";
std::string g_pending_commit;
bool g_incognito = false;
bool g_fixed_candidate_order = false;
bool g_enable_user_dict = true;
bool g_simplification = false;
bool g_ascii_punct = false;

/**
 * Standard UTF-8 ByteArray <-> std::string JNI helpers.
 * Bypasses Android JNI Modified UTF-8 string conversion so 4-byte
 * supplementary CJK Extension B~I codepoints (e.g. U+29F7E `𩽾` = F0 A9 BD BE) cross the
 * JNI boundary losslessly without corruption or ART abort.
 */
std::string jbyteArrayToStdString(JNIEnv* env, jbyteArray bytes) {
  if (!bytes) return "";
  const jsize len = env->GetArrayLength(bytes);
  if (len <= 0) return "";
  std::string out(static_cast<size_t>(len), '\0');
  env->GetByteArrayRegion(bytes, 0, len, reinterpret_cast<jbyte*>(out.data()));
  return out;
}

jbyteArray stdStringToJByteArray(JNIEnv* env, const std::string& utf8) {
  const jsize len = static_cast<jsize>(utf8.size());
  jbyteArray arr = env->NewByteArray(len);
  if (!arr) return nullptr;
  if (len > 0) {
    env->SetByteArrayRegion(arr, 0, len, reinterpret_cast<const jbyte*>(utf8.data()));
  }
  return arr;
}

std::string jstringToStdString(JNIEnv* env, jstring jstr) {
  if (!jstr) return "";
  const jsize len = env->GetStringLength(jstr);
  if (len <= 0) return "";
  std::vector<jchar> utf16(static_cast<size_t>(len));
  env->GetStringRegion(jstr, 0, len, utf16.data());

  std::string out;
  out.reserve(static_cast<size_t>(len) * 3);
  size_t i = 0;
  const size_t ulen = utf16.size();
  while (i < ulen) {
    uint32_t cp = utf16[i++];
    if (cp >= 0xD800 && cp <= 0xDBFF) {
      if (i < ulen && utf16[i] >= 0xDC00 && utf16[i] <= 0xDFFF) {
        uint32_t low = utf16[i++];
        cp = 0x10000 + (((cp - 0xD800) << 10) | (low - 0xDC00));
      } else {
        cp = 0xFFFD;
      }
    } else if (cp >= 0xDC00 && cp <= 0xDFFF) {
      cp = 0xFFFD;
    }

    if (cp < 0x80) {
      out.push_back(static_cast<char>(cp));
    } else if (cp < 0x800) {
      out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
      out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else if (cp < 0x10000) {
      out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
      out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
      out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else {
      out.push_back(static_cast<char>(0xF0 | (cp >> 18)));
      out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
      out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
      out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    }
  }
  return out;
}

jstring stdStringToJstring(JNIEnv* env, const std::string& str) {
  if (str.empty()) {
    return env->NewString(nullptr, 0);
  }
  std::vector<jchar> utf16;
  utf16.reserve(str.size());
  const auto* bytes = reinterpret_cast<const uint8_t*>(str.data());
  const size_t len = str.size();
  size_t i = 0;

  while (i < len) {
    uint32_t b0 = bytes[i];
    if (b0 < 0x80) {
      utf16.push_back(static_cast<jchar>(b0));
      i += 1;
    } else if ((b0 & 0xE0) == 0xC0) {
      if (i + 1 < len && (bytes[i + 1] & 0xC0) == 0x80) {
        uint32_t cp = ((b0 & 0x1F) << 6) | (bytes[i + 1] & 0x3F);
        if (cp >= 0x80) {
          utf16.push_back(static_cast<jchar>(cp));
        } else {
          utf16.push_back(0xFFFD);
        }
        i += 2;
      } else {
        utf16.push_back(0xFFFD);
        i += 1;
      }
    } else if ((b0 & 0xF0) == 0xE0) {
      if (i + 2 < len &&
          (bytes[i + 1] & 0xC0) == 0x80 &&
          (bytes[i + 2] & 0xC0) == 0x80) {
        uint32_t cp = ((b0 & 0x0F) << 12) |
                      ((bytes[i + 1] & 0x3F) << 6) |
                      (bytes[i + 2] & 0x3F);
        if (cp >= 0x800 && (cp < 0xD800 || cp > 0xDFFF)) {
          utf16.push_back(static_cast<jchar>(cp));
        } else {
          utf16.push_back(0xFFFD);
        }
        i += 3;
      } else {
        utf16.push_back(0xFFFD);
        i += 1;
      }
    } else if ((b0 & 0xF8) == 0xF0) {
      if (i + 3 < len &&
          (bytes[i + 1] & 0xC0) == 0x80 &&
          (bytes[i + 2] & 0xC0) == 0x80 &&
          (bytes[i + 3] & 0xC0) == 0x80) {
        uint32_t cp = ((b0 & 0x07) << 18) |
                      ((bytes[i + 1] & 0x3F) << 12) |
                      ((bytes[i + 2] & 0x3F) << 6) |
                      (bytes[i + 3] & 0x3F);
        if (cp >= 0x10000 && cp <= 0x10FFFF) {
          cp -= 0x10000;
          utf16.push_back(static_cast<jchar>(0xD800 + (cp >> 10)));
          utf16.push_back(static_cast<jchar>(0xDC00 + (cp & 0x3FF)));
        } else {
          utf16.push_back(0xFFFD);
        }
        i += 4;
      } else {
        utf16.push_back(0xFFFD);
        i += 1;
      }
    } else {
      utf16.push_back(0xFFFD);
      i += 1;
    }
  }
  return env->NewString(utf16.data(), static_cast<jsize>(utf16.size()));
}

void applySessionOptionsLocked(RimeApi* api, RimeSessionId sid) {
  if (!api || !sid) return;
  api->set_option(sid, "_incognito", g_incognito ? True : False);
  api->set_option(sid, "fixed_candidate_order", g_fixed_candidate_order ? True : False);
  api->set_option(sid, "enable_user_dict", g_enable_user_dict ? True : False);
  api->set_option(sid, "simplification", g_simplification ? True : False);
  api->set_option(sid, "ascii_punct", g_ascii_punct ? True : False);
}

bool verifyCompiledSchemaLocked(RimeApi* api, const std::string& schemaId) {
  if (!api || schemaId.empty()) return false;
  RimeConfig cfg = {nullptr};
  if (!api->schema_open(schemaId.c_str(), &cfg)) {
    return false;
  }
  char idBuf[256] = {0};
  Bool hasId = api->config_get_string(&cfg, "schema/schema_id", idBuf, sizeof(idBuf));
  api->config_close(&cfg);
  return hasId == True && schemaId == idBuf;
}

std::string findSchemaSourceFileLocked(const std::string& schemaId) {
  if (schemaId.empty()) return "";
  namespace fs = std::filesystem;
  const std::string fileName = schemaId + ".schema.yaml";
  std::error_code ec;
  if (!g_user_dir.empty()) {
    fs::path userPath = fs::path(g_user_dir) / fileName;
    if (fs::exists(userPath, ec) && fs::is_regular_file(userPath, ec)) {
      return userPath.string();
    }
  }
  if (!g_shared_dir.empty()) {
    fs::path sharedPath = fs::path(g_shared_dir) / fileName;
    if (fs::exists(sharedPath, ec) && fs::is_regular_file(sharedPath, ec)) {
      return sharedPath.string();
    }
  }
  return "";
}

bool deploySchemaWithDependenciesLocked(RimeApi* api, const std::string& schemaId) {
  if (!api || schemaId.empty()) return false;
  const std::string schemaPath = findSchemaSourceFileLocked(schemaId);
  if (schemaPath.empty()) {
    LOGE("Schema source file not found for '%s'", schemaId.c_str());
    return false;
  }

  std::ifstream fin(schemaPath);
  if (fin.is_open()) {
    std::ostringstream ss;
    ss << fin.rdbuf();
    std::string yamlText = ss.str();
    RimeConfig rawCfg = {nullptr};
    api->config_init(&rawCfg);
    if (api->config_load_string(&rawCfg, yamlText.c_str())) {
      RimeConfigIterator iter{};
      if (api->config_begin_list(&iter, &rawCfg, "schema/dependencies")) {
        std::vector<std::string> deps;
        while (api->config_next(&iter)) {
          const char* depId = api->config_get_cstring(&rawCfg, iter.path);
          if (depId && depId[0] != '\0' && schemaId != depId) {
            deps.emplace_back(depId);
          }
        }
        api->config_end(&iter);
        for (const auto& depId : deps) {
          if (!verifyCompiledSchemaLocked(api, depId)) {
            const std::string depPath = findSchemaSourceFileLocked(depId);
            if (!depPath.empty()) {
              LOGI("Deploying dependency schema '%s' from %s", depId.c_str(), depPath.c_str());
              api->deploy_schema(depPath.c_str());
            }
          }
        }
      }
    }
    api->config_close(&rawCfg);
  }

  LOGI("Deploying target schema '%s' from %s", schemaId.c_str(), schemaPath.c_str());
  Bool deployOk = api->deploy_schema(schemaPath.c_str());
  return deployOk == True && verifyCompiledSchemaLocked(api, schemaId);
}

void destroyProbeSessionLocked(RimeApi* api) {
  if (api && g_probe_session_id != 0) {
    if (api->find_session(g_probe_session_id)) {
      api->destroy_session(g_probe_session_id);
    }
    g_probe_session_id = 0;
  }
}

RimeSessionId ensureProbeSessionLocked(RimeApi* api) {
  if (!api || !g_initialized) return 0;
  if (g_probe_session_id == 0 || !api->find_session(g_probe_session_id)) {
    g_probe_session_id = api->create_session();
    if (g_probe_session_id != 0) {
      if (!g_active_schema.empty()) {
        api->select_schema(g_probe_session_id, g_active_schema.c_str());
      }
      api->set_option(g_probe_session_id, "_incognito", True);
      api->set_option(g_probe_session_id, "fixed_candidate_order", True);
      api->set_option(g_probe_session_id, "enable_user_dict", False);
    }
  }
  return g_probe_session_id;
}

std::vector<std::string> probeSingleCodeOnSessionLocked(
    RimeApi* api,
    RimeSessionId probeSid,
    const std::string& code,
    int maxCount) {
  std::vector<std::string> texts;
  if (!api || !probeSid || code.empty() || maxCount <= 0) return texts;

  api->clear_composition(probeSid);
  // Drain any stale commit before probing
  RIME_STRUCT(RimeCommit, staleCommit);
  if (api->get_commit(probeSid, &staleCommit)) {
    api->free_commit(&staleCommit);
  }

  for (char c : code) {
    api->process_key(probeSid, static_cast<unsigned char>(c), 0);
  }

  texts.reserve(static_cast<size_t>(maxCount));
  RIME_STRUCT(RimeCommit, commit);
  if (api->get_commit(probeSid, &commit)) {
    if (commit.text && commit.text[0] != '\0') {
      texts.emplace_back(commit.text);
    }
    api->free_commit(&commit);
  }

  RimeCandidateListIterator iter{};
  if (api->candidate_list_from_index(probeSid, &iter, 0)) {
    while (static_cast<int>(texts.size()) < maxCount && api->candidate_list_next(&iter)) {
      if (iter.candidate.text && iter.candidate.text[0] != '\0') {
        texts.emplace_back(iter.candidate.text);
      }
    }
    api->candidate_list_end(&iter);
  }

  api->clear_composition(probeSid);
  return texts;
}

RimeSessionId ensureSessionLocked(RimeApi* api) {
  if (!api || !g_initialized) return 0;
  if (g_session_id == 0 || !api->find_session(g_session_id)) {
    g_session_id = api->create_session();
    if (g_session_id != 0 && !g_active_schema.empty()) {
      api->select_schema(g_session_id, g_active_schema.c_str());
    }
    applySessionOptionsLocked(api, g_session_id);
  }
  return g_session_id;
}

void drainCommitLocked(RimeApi* api, RimeSessionId sid) {
  if (!api || !sid) return;
  RIME_STRUCT(RimeCommit, commit);
  if (api->get_commit(sid, &commit)) {
    if (commit.text) {
      g_pending_commit = commit.text;
    }
    api->free_commit(&commit);
  }
}

std::vector<std::string> collectCandidateItemsLocked(RimeApi* api, RimeSessionId sid, int maxCount) {
  std::vector<std::string> items;
  if (!api || !sid || maxCount <= 0) return items;
  items.reserve(static_cast<size_t>(maxCount));
  RimeCandidateListIterator iter{};
  if (api->candidate_list_from_index(sid, &iter, 0)) {
    int count = 0;
    while (count < maxCount && api->candidate_list_next(&iter)) {
      std::string text = iter.candidate.text ? iter.candidate.text : "";
      std::string comment = iter.candidate.comment ? iter.candidate.comment : "";
      items.push_back(text + "\t" + comment);
      ++count;
    }
    api->candidate_list_end(&iter);
  }
  return items;
}

}  // namespace

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* /* jvm */, void* /* reserved */) {
  declare_librime_module_dependencies();
  return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_typefree_rime_RimeBridge_nativeInitialize(
    JNIEnv* env,
    jobject /* thiz */,
    jstring sharedDir,
    jstring userDir) {
  std::lock_guard<std::mutex> lock(g_mutex);
  RimeApi* api = rime_get_api();
  if (!api) {
    LOGE("rime_get_api() returned null");
    return JNI_FALSE;
  }

  g_shared_dir = jstringToStdString(env, sharedDir);
  g_user_dir = jstringToStdString(env, userDir);

  RIME_STRUCT(RimeTraits, traits);
  traits.shared_data_dir = g_shared_dir.c_str();
  traits.user_data_dir = g_user_dir.c_str();
  traits.log_dir = "";
  traits.distribution_name = "TypeFree";
  traits.distribution_code_name = "typefree";
  traits.distribution_version = "3.6.0";
  traits.app_name = "rime.typefree";

  if (!g_setup_done) {
    declare_librime_module_dependencies();
    api->setup(&traits);
    g_setup_done = true;
  }

  if (g_initialized) {
    destroyProbeSessionLocked(api);
    if (g_session_id != 0) {
      api->destroy_session(g_session_id);
      g_session_id = 0;
    }
    api->finalize();
    g_initialized = false;
  }

  g_simplification = false;
  g_ascii_punct = false;
  api->initialize(&traits);
  g_initialized = true;
  LOGI("librime %s initialized (shared=%s, user=%s)",
       api->get_version() ? api->get_version() : "unknown",
       g_shared_dir.c_str(),
       g_user_dir.c_str());
  return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_typefree_rime_RimeBridge_nativeDeploy(
    JNIEnv* env,
    jobject /* thiz */,
    jstring schemaId) {
  std::lock_guard<std::mutex> lock(g_mutex);
  RimeApi* api = rime_get_api();
  if (!api || !g_initialized) return JNI_FALSE;

  std::string requestedSchema = jstringToStdString(env, schemaId);
  if (!requestedSchema.empty()) {
    g_active_schema = requestedSchema;
  }

  destroyProbeSessionLocked(api);
  if (g_session_id != 0) {
    api->destroy_session(g_session_id);
    g_session_id = 0;
  }

  if (api->start_maintenance(True)) {
    api->join_maintenance_thread();
  }

  if (!g_active_schema.empty() && !verifyCompiledSchemaLocked(api, g_active_schema)) {
    LOGW("Schema '%s' missing compiled output after maintenance; attempting explicit deploy_schema",
         g_active_schema.c_str());
    if (!deploySchemaWithDependenciesLocked(api, g_active_schema)) {
      LOGE("Explicit deploy_schema failed for '%s'", g_active_schema.c_str());
      return JNI_FALSE;
    }
  }

  g_session_id = api->create_session();
  if (g_session_id == 0) {
    LOGE("Failed to create Rime session after deploy");
    return JNI_FALSE;
  }

  if (!g_active_schema.empty()) {
    if (!verifyCompiledSchemaLocked(api, g_active_schema)) {
      LOGE("Compiled schema validation failed for '%s'", g_active_schema.c_str());
      return JNI_FALSE;
    }
    Bool selectOk = api->select_schema(g_session_id, g_active_schema.c_str());
    char currentSchema[256] = {0};
    Bool hasCurrent = api->get_current_schema(g_session_id, currentSchema, sizeof(currentSchema));
    if (!selectOk || !hasCurrent || g_active_schema != currentSchema) {
      LOGE("select_schema failed for '%s' (selectOk=%d, current='%s')",
           g_active_schema.c_str(),
           static_cast<int>(selectOk),
           currentSchema);
      return JNI_FALSE;
    }
    applySessionOptionsLocked(api, g_session_id);
  }
  LOGI("librime deploy complete (activeSchema=%s, session=%lu)",
       g_active_schema.c_str(),
       static_cast<unsigned long>(g_session_id));
  return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_typefree_rime_RimeBridge_nativeSelectSchema(
    JNIEnv* env,
    jobject /* thiz */,
    jstring schemaId) {
  std::lock_guard<std::mutex> lock(g_mutex);
  RimeApi* api = rime_get_api();
  if (!api || !g_initialized) return JNI_FALSE;

  std::string target = jstringToStdString(env, schemaId);
  if (target.empty()) return JNI_FALSE;
  g_active_schema = target;

  if (!verifyCompiledSchemaLocked(api, g_active_schema)) {
    LOGW("nativeSelectSchema: '%s' not yet compiled; attempting deploy_schema",
         g_active_schema.c_str());
    if (!deploySchemaWithDependenciesLocked(api, g_active_schema)) {
      LOGE("nativeSelectSchema: failed to compile '%s'", g_active_schema.c_str());
      return JNI_FALSE;
    }
  }

  RimeSessionId sid = ensureSessionLocked(api);
  if (!sid) return JNI_FALSE;
  Bool ok = api->select_schema(sid, g_active_schema.c_str());
  char currentSchema[256] = {0};
  Bool hasCurrent = api->get_current_schema(sid, currentSchema, sizeof(currentSchema));
  applySessionOptionsLocked(api, sid);
  if (g_probe_session_id != 0 && api->find_session(g_probe_session_id)) {
    api->select_schema(g_probe_session_id, g_active_schema.c_str());
    api->set_option(g_probe_session_id, "_incognito", True);
    api->set_option(g_probe_session_id, "fixed_candidate_order", True);
    api->set_option(g_probe_session_id, "enable_user_dict", False);
  }
  return (ok == True && hasCurrent == True && g_active_schema == currentSchema)
      ? JNI_TRUE
      : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_typefree_rime_RimeBridge_nativeProcessKey(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jint keyCode,
    jint mask) {
  std::lock_guard<std::mutex> lock(g_mutex);
  RimeApi* api = rime_get_api();
  RimeSessionId sid = ensureSessionLocked(api);
  if (!sid) return JNI_FALSE;

  g_pending_commit.clear();
  Bool handled = api->process_key(sid, static_cast<int>(keyCode), static_cast<int>(mask));
  drainCommitLocked(api, sid);
  return handled ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_typefree_rime_RimeBridge_nativeProcessKeyText(
    JNIEnv* env,
    jobject /* thiz */,
    jstring keys) {
  std::lock_guard<std::mutex> lock(g_mutex);
  RimeApi* api = rime_get_api();
  RimeSessionId sid = ensureSessionLocked(api);
  if (!sid) return JNI_FALSE;

  std::string seq = jstringToStdString(env, keys);
  g_pending_commit.clear();
  api->clear_composition(sid);
  if (seq.empty()) return JNI_TRUE;

  Bool ok = api->simulate_key_sequence(sid, seq.c_str());
  drainCommitLocked(api, sid);
  return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_typefree_rime_RimeBridge_nativeGetPreedit(
    JNIEnv* env,
    jobject /* thiz */) {
  std::lock_guard<std::mutex> lock(g_mutex);
  RimeApi* api = rime_get_api();
  RimeSessionId sid = ensureSessionLocked(api);
  if (!sid) return stdStringToJstring(env, "");

  std::string preedit;
  RIME_STRUCT(RimeContext, ctx);
  if (api->get_context(sid, &ctx)) {
    if (ctx.composition.preedit) {
      preedit = ctx.composition.preedit;
    }
    api->free_context(&ctx);
  }
  return stdStringToJstring(env, preedit);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_typefree_rime_RimeBridge_nativeGetPreeditUtf8(
    JNIEnv* env,
    jobject /* thiz */) {
  std::lock_guard<std::mutex> lock(g_mutex);
  RimeApi* api = rime_get_api();
  RimeSessionId sid = ensureSessionLocked(api);
  if (!sid) return stdStringToJByteArray(env, "");

  std::string preedit;
  RIME_STRUCT(RimeContext, ctx);
  if (api->get_context(sid, &ctx)) {
    if (ctx.composition.preedit) {
      preedit = ctx.composition.preedit;
    }
    api->free_context(&ctx);
  }
  return stdStringToJByteArray(env, preedit);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_typefree_rime_RimeBridge_nativeGetRawInput(
    JNIEnv* env,
    jobject /* thiz */) {
  std::lock_guard<std::mutex> lock(g_mutex);
  RimeApi* api = rime_get_api();
  RimeSessionId sid = ensureSessionLocked(api);
  if (!sid) return stdStringToJstring(env, "");

  const char* input = api->get_input(sid);
  return stdStringToJstring(env, input ? input : "");
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_typefree_rime_RimeBridge_nativeGetRawInputUtf8(
    JNIEnv* env,
    jobject /* thiz */) {
  std::lock_guard<std::mutex> lock(g_mutex);
  RimeApi* api = rime_get_api();
  RimeSessionId sid = ensureSessionLocked(api);
  if (!sid) return stdStringToJByteArray(env, "");

  const char* input = api->get_input(sid);
  return stdStringToJByteArray(env, input ? input : "");
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_typefree_rime_RimeBridge_nativeGetCandidates(
    JNIEnv* env,
    jobject /* thiz */,
    jint maxCount) {
  std::lock_guard<std::mutex> lock(g_mutex);
  jclass strCls = env->FindClass("java/lang/String");
  RimeApi* api = rime_get_api();
  RimeSessionId sid = ensureSessionLocked(api);
  if (!sid || maxCount <= 0) {
    return env->NewObjectArray(0, strCls, nullptr);
  }

  auto items = collectCandidateItemsLocked(api, sid, static_cast<int>(maxCount));
  jobjectArray arr = env->NewObjectArray(static_cast<jsize>(items.size()), strCls, nullptr);
  for (size_t i = 0; i < items.size(); ++i) {
    jstring js = stdStringToJstring(env, items[i]);
    env->SetObjectArrayElement(arr, static_cast<jsize>(i), js);
    env->DeleteLocalRef(js);
  }
  return arr;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_typefree_rime_RimeBridge_nativeGetCandidatesUtf8(
    JNIEnv* env,
    jobject /* thiz */,
    jint maxCount) {
  std::lock_guard<std::mutex> lock(g_mutex);
  jclass byteArrCls = env->FindClass("[B");
  RimeApi* api = rime_get_api();
  RimeSessionId sid = ensureSessionLocked(api);
  if (!sid || maxCount <= 0) {
    return env->NewObjectArray(0, byteArrCls, nullptr);
  }

  auto items = collectCandidateItemsLocked(api, sid, static_cast<int>(maxCount));
  jobjectArray arr = env->NewObjectArray(static_cast<jsize>(items.size()), byteArrCls, nullptr);
  for (size_t i = 0; i < items.size(); ++i) {
    jbyteArray jb = stdStringToJByteArray(env, items[i]);
    env->SetObjectArrayElement(arr, static_cast<jsize>(i), jb);
    env->DeleteLocalRef(jb);
  }
  return arr;
}

/**
 * Isolated read-only candidate prober for Track 5.2 702-slot (`26 + 676`) dynamic index building:
 * reuses a persistent `_incognito` probe session with `clear_composition` so probing never disturbs
 * active typing or `*.userdb`, and avoids repeated session creation / schema loading overhead.
 */
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_typefree_rime_RimeBridge_nativeProbeCandidatesForCodeUtf8(
    JNIEnv* env,
    jobject /* thiz */,
    jbyteArray codeUtf8,
    jint maxCount) {
  std::lock_guard<std::mutex> lock(g_mutex);
  jclass byteArrCls = env->FindClass("[B");
  RimeApi* api = rime_get_api();
  if (!api || !g_initialized || maxCount <= 0) {
    return env->NewObjectArray(0, byteArrCls, nullptr);
  }

  std::string code = jbyteArrayToStdString(env, codeUtf8);
  if (code.empty()) {
    return env->NewObjectArray(0, byteArrCls, nullptr);
  }

  RimeSessionId probeSid = ensureProbeSessionLocked(api);
  if (!probeSid) {
    return env->NewObjectArray(0, byteArrCls, nullptr);
  }

  auto texts = probeSingleCodeOnSessionLocked(api, probeSid, code, static_cast<int>(maxCount));
  jobjectArray arr = env->NewObjectArray(static_cast<jsize>(texts.size()), byteArrCls, nullptr);
  for (size_t i = 0; i < texts.size(); ++i) {
    jbyteArray jb = stdStringToJByteArray(env, texts[i]);
    env->SetObjectArrayElement(arr, static_cast<jsize>(i), jb);
    env->DeleteLocalRef(jb);
  }
  return arr;
}

/**
 * Single-session batch prober for Track 5.2 702-slot (`26 + 676`) dynamic index building:
 * creates one temporary `_incognito` session, selects schema once, probes all codes in a single
 * JNI crossing with `clear_composition` between slots, and destroys the temporary session.
 * Each slot result is returned as UTF-8 bytes joined by ASCII Unit Separator (`0x1F`).
 */
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_typefree_rime_RimeBridge_nativeProbeBatchCodesUtf8(
    JNIEnv* env,
    jobject /* thiz */,
    jobjectArray codesUtf8,
    jint maxCount) {
  std::lock_guard<std::mutex> lock(g_mutex);
  jclass byteArrCls = env->FindClass("[B");
  RimeApi* api = rime_get_api();
  if (!api || !g_initialized || !codesUtf8 || maxCount <= 0) {
    return env->NewObjectArray(0, byteArrCls, nullptr);
  }

  const jsize total = env->GetArrayLength(codesUtf8);
  if (total <= 0) {
    return env->NewObjectArray(0, byteArrCls, nullptr);
  }

  RimeSessionId probeSid = api->create_session();
  if (!probeSid) {
    return env->NewObjectArray(0, byteArrCls, nullptr);
  }
  if (!g_active_schema.empty()) {
    api->select_schema(probeSid, g_active_schema.c_str());
  }
  api->set_option(probeSid, "_incognito", True);
  api->set_option(probeSid, "fixed_candidate_order", True);
  api->set_option(probeSid, "enable_user_dict", False);

  jobjectArray resultArr = env->NewObjectArray(total, byteArrCls, nullptr);
  const int limit = static_cast<int>(maxCount);
  for (jsize i = 0; i < total; ++i) {
    auto* codeElem = static_cast<jbyteArray>(env->GetObjectArrayElement(codesUtf8, i));
    std::string code = jbyteArrayToStdString(env, codeElem);
    if (codeElem) {
      env->DeleteLocalRef(codeElem);
    }

    auto texts = probeSingleCodeOnSessionLocked(api, probeSid, code, limit);
    std::string joined;
    for (size_t k = 0; k < texts.size(); ++k) {
      if (k > 0) joined.push_back('\x1f');
      joined.append(texts[k]);
    }
    jbyteArray jb = stdStringToJByteArray(env, joined);
    env->SetObjectArrayElement(resultArr, i, jb);
    if (jb) {
      env->DeleteLocalRef(jb);
    }
  }

  api->destroy_session(probeSid);
  return resultArr;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_typefree_rime_RimeBridge_nativeSelectCandidate(
    JNIEnv* env,
    jobject /* thiz */,
    jint index) {
  std::lock_guard<std::mutex> lock(g_mutex);
  RimeApi* api = rime_get_api();
  RimeSessionId sid = ensureSessionLocked(api);
  if (!sid || index < 0) return nullptr;

  g_pending_commit.clear();
  if (!api->select_candidate(sid, static_cast<size_t>(index))) {
    return nullptr;
  }
  drainCommitLocked(api, sid);
  if (!g_pending_commit.empty()) {
    std::string out = g_pending_commit;
    g_pending_commit.clear();
    return stdStringToJstring(env, out);
  }
  return nullptr;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_typefree_rime_RimeBridge_nativeSelectCandidateUtf8(
    JNIEnv* env,
    jobject /* thiz */,
    jint index) {
  std::lock_guard<std::mutex> lock(g_mutex);
  RimeApi* api = rime_get_api();
  RimeSessionId sid = ensureSessionLocked(api);
  if (!sid || index < 0) return nullptr;

  g_pending_commit.clear();
  if (!api->select_candidate(sid, static_cast<size_t>(index))) {
    return nullptr;
  }
  drainCommitLocked(api, sid);
  if (!g_pending_commit.empty()) {
    std::string out = g_pending_commit;
    g_pending_commit.clear();
    return stdStringToJByteArray(env, out);
  }
  return nullptr;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_typefree_rime_RimeBridge_nativeCommitComposition(
    JNIEnv* env,
    jobject /* thiz */) {
  std::lock_guard<std::mutex> lock(g_mutex);
  RimeApi* api = rime_get_api();
  RimeSessionId sid = ensureSessionLocked(api);
  if (!sid) return nullptr;

  g_pending_commit.clear();
  api->commit_composition(sid);
  drainCommitLocked(api, sid);
  if (!g_pending_commit.empty()) {
    std::string out = g_pending_commit;
    g_pending_commit.clear();
    return stdStringToJstring(env, out);
  }
  return nullptr;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_typefree_rime_RimeBridge_nativeCommitCompositionUtf8(
    JNIEnv* env,
    jobject /* thiz */) {
  std::lock_guard<std::mutex> lock(g_mutex);
  RimeApi* api = rime_get_api();
  RimeSessionId sid = ensureSessionLocked(api);
  if (!sid) return nullptr;

  g_pending_commit.clear();
  api->commit_composition(sid);
  drainCommitLocked(api, sid);
  if (!g_pending_commit.empty()) {
    std::string out = g_pending_commit;
    g_pending_commit.clear();
    return stdStringToJByteArray(env, out);
  }
  return nullptr;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_typefree_rime_RimeBridge_nativeGetCommit(
    JNIEnv* env,
    jobject /* thiz */) {
  std::lock_guard<std::mutex> lock(g_mutex);
  RimeApi* api = rime_get_api();
  RimeSessionId sid = ensureSessionLocked(api);
  if (sid) {
    drainCommitLocked(api, sid);
  }
  if (g_pending_commit.empty()) return nullptr;
  std::string out = g_pending_commit;
  g_pending_commit.clear();
  return stdStringToJstring(env, out);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_typefree_rime_RimeBridge_nativeGetCommitUtf8(
    JNIEnv* env,
    jobject /* thiz */) {
  std::lock_guard<std::mutex> lock(g_mutex);
  RimeApi* api = rime_get_api();
  RimeSessionId sid = ensureSessionLocked(api);
  if (sid) {
    drainCommitLocked(api, sid);
  }
  if (g_pending_commit.empty()) return nullptr;
  std::string out = g_pending_commit;
  g_pending_commit.clear();
  return stdStringToJByteArray(env, out);
}

extern "C" JNIEXPORT void JNICALL
Java_com_typefree_rime_RimeBridge_nativeClearComposition(
    JNIEnv* /* env */,
    jobject /* thiz */) {
  std::lock_guard<std::mutex> lock(g_mutex);
  RimeApi* api = rime_get_api();
  RimeSessionId sid = ensureSessionLocked(api);
  g_pending_commit.clear();
  if (sid) {
    api->clear_composition(sid);
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_typefree_rime_RimeBridge_nativeSetOption(
    JNIEnv* env,
    jobject /* thiz */,
    jstring option,
    jboolean value) {
  std::lock_guard<std::mutex> lock(g_mutex);
  std::string opt = jstringToStdString(env, option);
  bool val = (value == JNI_TRUE);
  if (opt == "_incognito") {
    g_incognito = val;
  } else if (opt == "fixed_candidate_order") {
    g_fixed_candidate_order = val;
  } else if (opt == "enable_user_dict") {
    g_enable_user_dict = val;
  } else if (opt == "simplification") {
    g_simplification = val;
  } else if (opt == "ascii_punct") {
    g_ascii_punct = val;
  }
  RimeApi* api = rime_get_api();
  RimeSessionId sid = ensureSessionLocked(api);
  if (sid && !opt.empty()) {
    api->set_option(sid, opt.c_str(), val ? True : False);
  }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_typefree_rime_RimeBridge_nativeGetOption(
    JNIEnv* env,
    jobject /* thiz */,
    jstring option) {
  std::lock_guard<std::mutex> lock(g_mutex);
  std::string opt = jstringToStdString(env, option);
  if (opt == "_incognito") return g_incognito ? JNI_TRUE : JNI_FALSE;
  if (opt == "fixed_candidate_order") return g_fixed_candidate_order ? JNI_TRUE : JNI_FALSE;
  if (opt == "enable_user_dict") return g_enable_user_dict ? JNI_TRUE : JNI_FALSE;
  if (opt == "simplification") return g_simplification ? JNI_TRUE : JNI_FALSE;
  if (opt == "ascii_punct") return g_ascii_punct ? JNI_TRUE : JNI_FALSE;
  RimeApi* api = rime_get_api();
  RimeSessionId sid = ensureSessionLocked(api);
  if (!sid || opt.empty()) return JNI_FALSE;
  return api->get_option(sid, opt.c_str()) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_typefree_rime_RimeBridge_nativeSyncUserData(
    JNIEnv* /* env */,
    jobject /* thiz */) {
  std::lock_guard<std::mutex> lock(g_mutex);
  RimeApi* api = rime_get_api();
  if (!api || !g_initialized) return JNI_FALSE;
  return api->sync_user_data() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_typefree_rime_RimeBridge_nativeGetVersion(
    JNIEnv* env,
    jobject /* thiz */) {
  RimeApi* api = rime_get_api();
  const char* ver = (api && api->get_version) ? api->get_version() : "unknown";
  return stdStringToJstring(env, ver ? ver : "unknown");
}
