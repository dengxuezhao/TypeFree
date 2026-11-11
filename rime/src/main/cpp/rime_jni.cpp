// SPDX-FileCopyrightText: 2015 - 2026 Rime community / TypeFree v2.7
// SPDX-License-Identifier: GPL-3.0-or-later

#include <jni.h>
#include <rime_api.h>
#include <android/log.h>

#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

#define LOG_TAG "TypeFreeRimeJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

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
std::string g_shared_dir;
std::string g_user_dir;
std::string g_active_schema = "rime_ice";
std::string g_pending_commit;
bool g_incognito = false;
bool g_fixed_candidate_order = false;

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
  traits.log_dir = "";  // log to Android logcat only
  traits.distribution_name = "TypeFree";
  traits.distribution_code_name = "typefree";
  traits.distribution_version = "2.7.0";
  traits.app_name = "rime.typefree";

  if (!g_setup_done) {
    declare_librime_module_dependencies();
    api->setup(&traits);
    g_setup_done = true;
  }

  if (g_initialized) {
    if (g_session_id != 0) {
      api->destroy_session(g_session_id);
      g_session_id = 0;
    }
    api->finalize();
    g_initialized = false;
  }

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

  if (g_session_id != 0) {
    api->destroy_session(g_session_id);
    g_session_id = 0;
  }

  if (api->start_maintenance(True)) {
    api->join_maintenance_thread();
  }

  g_session_id = api->create_session();
  if (g_session_id != 0 && !g_active_schema.empty()) {
    api->select_schema(g_session_id, g_active_schema.c_str());
    applySessionOptionsLocked(api, g_session_id);
  }
  LOGI("librime deploy complete (activeSchema=%s, session=%lu)",
       g_active_schema.c_str(),
       static_cast<unsigned long>(g_session_id));
  return g_session_id != 0 ? JNI_TRUE : JNI_FALSE;
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

  RimeSessionId sid = ensureSessionLocked(api);
  if (!sid) return JNI_FALSE;
  Bool ok = api->select_schema(sid, g_active_schema.c_str());
  applySessionOptionsLocked(api, sid);
  return ok ? JNI_TRUE : JNI_FALSE;
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

  std::vector<std::string> items;
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

  jobjectArray arr = env->NewObjectArray(static_cast<jsize>(items.size()), strCls, nullptr);
  for (size_t i = 0; i < items.size(); ++i) {
    jstring js = stdStringToJstring(env, items[i]);
    env->SetObjectArrayElement(arr, static_cast<jsize>(i), js);
    env->DeleteLocalRef(js);
  }
  return arr;
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
