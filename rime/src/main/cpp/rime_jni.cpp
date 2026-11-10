#include <jni.h>
#include <dlfcn.h>
#include <dirent.h>
#include <sys/stat.h>
#include <algorithm>
#include <cctype>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <fstream>
#include <mutex>
#include <sstream>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <vector>

#ifdef __ANDROID__
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "TypeFreeRimeJNI", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "TypeFreeRimeJNI", __VA_ARGS__)
#else
#define LOGI(...)
#define LOGW(...)
#endif

// ============================================================================
// 1. Standard librime C API Declarations (compatible with Trime / rime_api.h)
// ============================================================================

typedef uintptr_t RimeSessionId;
typedef int Bool;

#ifndef False
#define False 0
#endif
#ifndef True
#define True 1
#endif

#define RIME_STRUCT_INIT(Type, var) \
    ((var).data_size = sizeof(Type) - sizeof((var).data_size))

typedef struct rime_traits_t {
    int data_size;
    const char* shared_data_dir;
    const char* user_data_dir;
    const char* distribution_name;
    const char* distribution_code_name;
    const char* distribution_version;
    const char* app_name;
    const char** modules;
    int min_log_level;
    const char* log_dir;
    const char* prebuilt_data_dir;
    const char* staging_dir;
} RimeTraits;

typedef struct rime_composition_t {
    int length;
    int cursor_pos;
    int sel_start;
    int sel_end;
    const char* preedit;
} RimeComposition;

typedef struct rime_candidate_t {
    char* text;
    char* comment;
    void* reserved;
} RimeCandidate;

typedef struct rime_menu_t {
    int page_size;
    int page_no;
    Bool is_last_page;
    int highlighted_candidate_index;
    int num_candidates;
    RimeCandidate* candidates;
    char* select_keys;
} RimeMenu;

typedef struct rime_commit_t {
    int data_size;
    char* text;
} RimeCommit;

typedef struct rime_context_t {
    int data_size;
    RimeComposition composition;
    RimeMenu menu;
    char* commit_text_preview;
    char** select_labels;
} RimeContext;

typedef struct rime_api_t {
    int data_size;
    void (*setup)(RimeTraits* traits);
    void (*set_notification_handler)(void (*handler)(void*, RimeSessionId, const char*, const char*), void* context_object);
    void (*initialize)(RimeTraits* traits);
    void (*finalize)(void);
    Bool (*start_maintenance)(Bool full_check);
    Bool (*is_maintenance_mode)(void);
    void (*join_maintenance_thread)(void);
    void (*deployer_initialize)(RimeTraits* traits);
    Bool (*prebuild)(void);
    Bool (*deploy)(void);
    Bool (*deploy_schema)(const char* schema_file);
    Bool (*deploy_config_file)(const char* file_name, const char* version_key);
    Bool (*sync_user_data)(void);
    RimeSessionId (*create_session)(void);
    Bool (*find_session)(RimeSessionId session_id);
    Bool (*destroy_session)(RimeSessionId session_id);
    void (*cleanup_stale_sessions)(void);
    void (*cleanup_all_sessions)(void);
    Bool (*process_key)(RimeSessionId session_id, int keycode, int mask);
    Bool (*commit_composition)(RimeSessionId session_id);
    void (*clear_composition)(RimeSessionId session_id);
    Bool (*get_commit)(RimeSessionId session_id, RimeCommit* commit);
    Bool (*free_commit)(RimeCommit* commit);
    Bool (*get_context)(RimeSessionId session_id, RimeContext* context);
    Bool (*free_context)(RimeContext* context);
    Bool (*get_status)(RimeSessionId session_id, void* status);
    Bool (*free_status)(void* status);
    void (*set_option)(RimeSessionId session_id, const char* option, Bool value);
    Bool (*get_option)(RimeSessionId session_id, const char* option);
    void (*set_property)(RimeSessionId session_id, const char* prop, const char* value);
    Bool (*get_property)(RimeSessionId session_id, const char* prop, char* value, size_t buffer_size);
    Bool (*get_schema_list)(void* schema_list);
    void (*free_schema_list)(void* schema_list);
    Bool (*get_current_schema)(RimeSessionId session_id, char* schema_id, size_t buffer_size);
    Bool (*select_schema)(RimeSessionId session_id, const char* schema_id);
    const char* (*get_version)(void);
    const char* (*get_shared_data_dir)(void);
    const char* (*get_user_data_dir)(void);
    const char* (*get_sync_dir)(void);
    const char* (*get_user_id)(void);
    void (*get_user_data_sync_dir)(char* dir, size_t buffer_size);
    Bool (*simulate_key_sequence)(RimeSessionId session_id, const char* key_sequence);
    Bool (*register_module)(void* module);
    void* (*find_module)(const char* module_name);
    Bool (*run_task)(const char* task_name);
    const char* (*get_staging_dir)(void);
    const char* (*get_prebuilt_data_dir)(void);
    void (*get_shared_data_dir_s)(char* dir, size_t buffer_size);
    void (*get_user_data_dir_s)(char* dir, size_t buffer_size);
    void (*get_prebuilt_data_dir_s)(char* dir, size_t buffer_size);
    void (*get_staging_dir_s)(char* dir, size_t buffer_size);
    void (*get_sync_dir_s)(char* dir, size_t buffer_size);
    Bool (* select_candidate)(RimeSessionId session_id, size_t index);
    Bool (* select_candidate_on_current_page)(RimeSessionId session_id, size_t index);
} RimeApi;

typedef RimeApi* (*rime_get_api_func)(void);

// ============================================================================
// 2. Built-in Native C++17 Rime-Compatible Pinyin & Double-Pinyin Engine
// ============================================================================

namespace {

struct DictEntry {
    std::string word;
    std::string pinyin; // space-separated syllables e.g. "ni hao"
    std::string compactPinyin; // e.g. "nihao"
    std::string initials; // e.g. "nh"
    int syllableCount = 1;
    int64_t baseWeight = 0;
    int orderIndex = 0;
};

struct UserDbRecord {
    std::string word;
    std::string pinyin;
    int64_t commits = 0;
    int64_t lastTick = 0;
};

struct CandidateItem {
    std::string text;
    std::string comment;
    std::string pinyin;
    int64_t score = 0;
    int orderIndex = 0;
};

static const std::unordered_set<std::string>& validPinyinSyllables() {
    static const std::unordered_set<std::string> kSyllables = {
        "a", "ai", "an", "ang", "ao",
        "ba", "bai", "ban", "bang", "bao", "bei", "ben", "beng", "bi", "bian", "biao", "bie", "bin", "bing", "bo", "bu",
        "ca", "cai", "can", "cang", "cao", "ce", "cen", "ceng", "cha", "chai", "chan", "chang", "chao", "che", "chen", "cheng", "chi", "chong", "chou", "chu", "chua", "chuai", "chuan", "chuang", "chui", "chun", "chuo", "ci", "cong", "cou", "cu", "cuan", "cui", "cun", "cuo",
        "da", "dai", "dan", "dang", "dao", "de", "dei", "den", "deng", "di", "dia", "dian", "diao", "die", "ding", "diu", "dong", "dou", "du", "duan", "dui", "dun", "duo",
        "e", "ei", "en", "eng", "er",
        "fa", "fan", "fang", "fei", "fen", "feng", "fiao", "fo", "fou", "fu",
        "ga", "gai", "gan", "gang", "gao", "ge", "gei", "gen", "geng", "gong", "gou", "gu", "gua", "guai", "guan", "guang", "gui", "gun", "guo",
        "ha", "hai", "han", "hang", "hao", "he", "hei", "hen", "heng", "hong", "hou", "hu", "hua", "huai", "huan", "huang", "hui", "hun", "huo",
        "ji", "jia", "jian", "jiang", "jiao", "jie", "jin", "jing", "jiong", "jiu", "ju", "juan", "jue", "jun",
        "ka", "kai", "kan", "kang", "kao", "ke", "kei", "ken", "keng", "kong", "kou", "ku", "kua", "kuai", "kuan", "kuang", "kui", "kun", "kuo",
        "la", "lai", "lan", "lang", "lao", "le", "lei", "leng", "li", "lia", "lian", "liang", "liao", "lie", "lin", "ling", "liu", "lo", "long", "lou", "lu", "luan", "lue", "lve", "lun", "luo", "lv",
        "ma", "mai", "man", "mang", "mao", "me", "mei", "men", "meng", "mi", "mian", "miao", "mie", "min", "ming", "miu", "mo", "mou", "mu",
        "na", "nai", "nan", "nang", "nao", "ne", "nei", "nen", "neng", "ni", "nian", "niang", "niao", "nie", "nin", "ning", "niu", "nong", "nou", "nu", "nuan", "nue", "nve", "nuo", "nv",
        "o", "ou",
        "pa", "pai", "pan", "pang", "pao", "pei", "pen", "peng", "pi", "pian", "piao", "pie", "pin", "ping", "po", "pou", "pu",
        "qi", "qia", "qian", "qiang", "qiao", "qie", "qin", "qing", "qiong", "qiu", "qu", "quan", "que", "qun",
        "ran", "rang", "rao", "re", "ren", "reng", "ri", "rong", "rou", "ru", "rua", "ruan", "rui", "run", "ruo",
        "sa", "sai", "san", "sang", "sao", "se", "sen", "seng", "sha", "shai", "shan", "shang", "shao", "she", "shei", "shen", "sheng", "shi", "shou", "shu", "shua", "shuai", "shuan", "shuang", "shui", "shun", "shuo", "si", "song", "sou", "su", "suan", "sui", "sun", "suo",
        "ta", "tai", "tan", "tang", "tao", "te", "tei", "teng", "ti", "tian", "tiao", "tie", "ting", "tong", "tou", "tu", "tuan", "tui", "tun", "tuo",
        "wa", "wai", "wan", "wang", "wei", "wen", "weng", "wo", "wu",
        "xi", "xia", "xian", "xiang", "xiao", "xie", "xin", "xing", "xiong", "xiu", "xu", "xuan", "xue", "xun",
        "ya", "yan", "yang", "yao", "ye", "yi", "yin", "ying", "yo", "yong", "you", "yu", "yuan", "yue", "yun",
        "za", "zai", "zan", "zang", "zao", "ze", "zei", "zen", "zeng", "zha", "zhai", "zhan", "zhang", "zhao", "zhe", "zhei", "zhen", "zheng", "zhi", "zhong", "zhou", "zhu", "zhua", "zhuai", "zhuan", "zhuang", "zhui", "zhun", "zhuo", "zi", "zong", "zou", "zu", "zuan", "zui", "zun", "zuo"
    };
    return kSyllables;
}

// Converts a 2-letter Xiaohe Double Pinyin code (double_pinyin_flypy) to standard Pinyin syllable
static std::string mapFlypyDoublePinyinSyllable(char c0, char c1) {
    std::string pair;
    pair.push_back(c0);
    pair.push_back(c1);

    // 1. Zero-initial syllables in Xiaohe Double Pinyin
    if (pair == "aa") return "a";
    if (pair == "ai") return "ai";
    if (pair == "an") return "an";
    if (pair == "ah") return "ang";
    if (pair == "ao") return "ao";
    if (pair == "ee") return "e";
    if (pair == "ei") return "ei";
    if (pair == "en") return "en";
    if (pair == "eg") return "eng";
    if (pair == "er") return "er";
    if (pair == "oo") return "o";
    if (pair == "ou") return "ou";

    // 2. Initial
    std::string init;
    if (c0 == 'v') init = "zh";
    else if (c0 == 'i') init = "ch";
    else if (c0 == 'u') init = "sh";
    else init.push_back(c0);

    // 3. Final
    std::string fin;
    switch (c1) {
        case 'a': fin = "a"; break;
        case 'b': fin = "in"; break;
        case 'c': fin = "ao"; break;
        case 'd': fin = "ai"; break;
        case 'e': fin = "e"; break;
        case 'f': fin = "en"; break;
        case 'g': fin = "eng"; break;
        case 'h': fin = "ang"; break;
        case 'i': fin = "i"; break;
        case 'j': fin = "an"; break;
        case 'k':
            if (c0 == 'g' || c0 == 'k' || c0 == 'h' || c0 == 'v' || c0 == 'i' || c0 == 'u' || c0 == 'r') {
                fin = "uai";
            } else {
                fin = "ing";
            }
            break;
        case 'l':
            if (c0 == 'n' || c0 == 'l' || c0 == 'j' || c0 == 'q' || c0 == 'x') {
                fin = "iang";
            } else {
                fin = "uang";
            }
            break;
        case 'm': fin = "ian"; break;
        case 'n': fin = "iao"; break;
        case 'o':
            if (c0 == 'b' || c0 == 'p' || c0 == 'm' || c0 == 'f' || c0 == 'w') {
                fin = "o";
            } else {
                fin = "uo";
            }
            break;
        case 'p': fin = "ie"; break;
        case 'q': fin = "iu"; break;
        case 'r': fin = "uan"; break;
        case 's':
            if (c0 == 'j' || c0 == 'q' || c0 == 'x') {
                fin = "iong";
            } else {
                fin = "ong";
            }
            break;
        case 't':
            if (c0 == 'n' || c0 == 'l') {
                fin = "ve";
            } else {
                fin = "ue";
            }
            break;
        case 'u': fin = "u"; break;
        case 'v':
            if (c0 == 'n' || c0 == 'l') {
                fin = "v";
            } else if (c0 == 'j' || c0 == 'q' || c0 == 'x' || c0 == 'y') {
                fin = "u";
            } else {
                fin = "ui";
            }
            break;
        case 'w': fin = "ei"; break;
        case 'x':
            if (c0 == 'd' || c0 == 'l' || c0 == 'j' || c0 == 'q' || c0 == 'x') {
                fin = "ia";
            } else {
                fin = "ua";
            }
            break;
        case 'y': fin = "un"; break;
        case 'z': fin = "ou"; break;
        default: fin.push_back(c1); break;
    }

    std::string combined = init + fin;
    const auto& valid = validPinyinSyllables();
    if (valid.find(combined) != valid.end()) {
        return combined;
    }
    if (combined == "nue") return "nve";
    if (combined == "lue") return "lve";
    return pair;
}

class NativeRimeFallbackEngine {
public:
    bool initialize(const std::string& sharedDir, const std::string& userDir) {
        std::lock_guard<std::mutex> lock(mutex_);
        sharedDir_ = sharedDir;
        userDir_ = userDir;
        loadLexiconsLocked();
        loadUserDbLocked();
        initialized_ = true;
        return true;
    }

    bool deploy(const std::string& schemaId) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!schemaId.empty()) {
            schemaId_ = schemaId;
        }
        loadLexiconsLocked();
        loadUserDbLocked();
        clearLocked();
        return true;
    }

    bool selectSchema(const std::string& schemaId) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (schemaId.empty()) return false;
        schemaId_ = schemaId;
        clearLocked();
        return true;
    }

    void setOption(const std::string& option, bool value) {
        std::lock_guard<std::mutex> lock(mutex_);
        options_[option] = value;
        if (option == "_incognito" || option == "incognito") {
            incognito_ = value;
        } else if (option == "fixed_candidate_order") {
            fixedOrder_ = value;
        } else if (option == "enable_user_dict") {
            enableUserDict_ = value;
        } else if (option == "ascii_mode") {
            asciiMode_ = value;
        }
        if (!rawInput_.empty()) {
            recomputeCandidatesLocked();
        }
    }

    bool getOption(const std::string& option) {
        std::lock_guard<std::mutex> lock(mutex_);
        auto it = options_.find(option);
        return it != options_.end() ? it->second : false;
    }

    bool processKey(int keyCode, int /*mask*/) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (asciiMode_) return false;

        // Backspace (0xFF08 or 8 or 127)
        if (keyCode == 0xFF08 || keyCode == 8 || keyCode == 127) {
            if (rawInput_.empty()) return false;
            rawInput_.pop_back();
            recomputeCandidatesLocked();
            return true;
        }
        // Escape (0xFF1B or 27)
        if (keyCode == 0xFF1B || keyCode == 27) {
            if (rawInput_.empty()) return false;
            clearLocked();
            return true;
        }
        // Space (' ')
        if (keyCode == ' ') {
            if (rawInput_.empty()) return false;
            if (!candidates_.empty()) {
                commitCandidateLocked(0);
            } else {
                clearLocked();
            }
            return true;
        }
        // Return / Enter (0xFF0D or '\r' or '\n')
        if (keyCode == 0xFF0D || keyCode == '\r' || keyCode == '\n') {
            if (rawInput_.empty()) return false;
            pendingCommit_ = rawInput_;
            clearLocked();
            return true;
        }
        if ((keyCode >= 'a' && keyCode <= 'z') || keyCode == '\'') {
            rawInput_.push_back(static_cast<char>(keyCode));
            recomputeCandidatesLocked();
            return true;
        }
        return false;
    }

    void processKeyText(const std::string& keys) {
        std::lock_guard<std::mutex> lock(mutex_);
        rawInput_ = keys;
        recomputeCandidatesLocked();
    }

    std::string selectCandidate(int index) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (index < 0 || index >= static_cast<int>(candidates_.size())) {
            return "";
        }
        return commitCandidateLocked(index);
    }

    std::string getCommit() {
        std::lock_guard<std::mutex> lock(mutex_);
        std::string out = pendingCommit_;
        pendingCommit_.clear();
        return out;
    }

    void clearComposition() {
        std::lock_guard<std::mutex> lock(mutex_);
        clearLocked();
    }

    bool learnWord(const std::string& pinyinKey, const std::string& word) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (incognito_ || !enableUserDict_ || fixedOrder_) {
            return false;
        }
        recordUserCommitLocked(pinyinKey, word);
        return true;
    }

    std::string preedit() const { return preedit_; }
    std::string rawInput() const { return rawInput_; }
    const std::vector<CandidateItem>& candidates() const { return candidates_; }

private:
    void clearLocked() {
        rawInput_.clear();
        preedit_.clear();
        candidates_.clear();
    }

    static std::string normalizePinyin(const std::string& py) {
        std::string out;
        out.reserve(py.size());
        bool prevSpace = true;
        for (char c : py) {
            if (c >= 'A' && c <= 'Z') c = static_cast<char>(c - 'A' + 'a');
            if (c >= 'a' && c <= 'z') {
                out.push_back(c);
                prevSpace = false;
            } else if ((c == ' ' || c == '\'') && !prevSpace) {
                out.push_back(' ');
                prevSpace = true;
            }
        }
        if (!out.empty() && out.back() == ' ') out.pop_back();
        return out;
    }

    static void parseEntryLine(
        const std::string& line,
        std::vector<DictEntry>& outEntries,
        int& orderCounter
    ) {
        if (line.empty() || line[0] == '#') return;
        size_t t1 = line.find('\t');
        if (t1 == std::string::npos || t1 == 0) return;
        std::string col0 = line.substr(0, t1);
        size_t t2 = line.find('\t', t1 + 1);
        std::string col1 = (t2 == std::string::npos)
            ? line.substr(t1 + 1)
            : line.substr(t1 + 1, t2 - t1 - 1);
        std::string col2 = (t2 == std::string::npos) ? "" : line.substr(t2 + 1);

        // Support both Rime format (word\tpinyin\tweight) and code-first TSV (code\tword1\tword2)
        bool col0IsAscii = true;
        for (unsigned char ch : col0) {
            if (!(std::islower(ch) || ch == ' ')) {
                col0IsAscii = false;
                break;
            }
        }

        if (col0IsAscii && !col1.empty() && static_cast<unsigned char>(col1[0]) >= 0x80) {
            // Code-first TSV: code \t word1 \t word2 ...
            std::string py = normalizePinyin(col0);
            if (py.empty()) return;
            std::stringstream ss(line.substr(t1 + 1));
            std::string w;
            int rank = 0;
            while (std::getline(ss, w, '\t')) {
                if (w.empty()) continue;
                addDictEntry(w, py, std::max<int64_t>(100, 10000 - rank * 50), outEntries, orderCounter);
                rank++;
            }
            return;
        }

        std::string py = normalizePinyin(col1);
        if (col0.empty() || py.empty()) return;
        int64_t wt = 1000;
        if (!col2.empty()) {
            char* endPtr = nullptr;
            long long parsed = std::strtoll(col2.c_str(), &endPtr, 10);
            if (endPtr != col2.c_str() && parsed >= 0) {
                wt = parsed;
            }
        }
        addDictEntry(col0, py, wt, outEntries, orderCounter);
    }

    static void addDictEntry(
        const std::string& word,
        const std::string& py,
        int64_t weight,
        std::vector<DictEntry>& outEntries,
        int& orderCounter
    ) {
        DictEntry e;
        e.word = word;
        e.pinyin = py;
        e.baseWeight = weight;
        e.orderIndex = orderCounter++;

        int syls = 1;
        bool atStart = true;
        for (char c : py) {
            if (c == ' ') {
                syls++;
                atStart = true;
            } else {
                e.compactPinyin.push_back(c);
                if (atStart) {
                    e.initials.push_back(c);
                    atStart = false;
                }
            }
        }
        e.syllableCount = syls;
        outEntries.push_back(std::move(e));
    }

    void loadFileIntoEntries(const std::string& path, int& orderCounter) {
        std::ifstream in(path);
        if (!in.is_open()) return;
        std::string line;
        bool inYamlHeader = false;
        while (std::getline(in, line)) {
            if (!line.empty() && line.back() == '\r') line.pop_back();
            if (line == "---") {
                inYamlHeader = true;
                continue;
            }
            if (inYamlHeader) {
                if (line == "...") inYamlHeader = false;
                continue;
            }
            parseEntryLine(line, entries_, orderCounter);
        }
    }

    void loadLexiconsLocked() {
        entries_.clear();
        byExactPinyin_.clear();
        byCompactPinyin_.clear();
        byInitials_.clear();
        byFirstSyllable_.clear();

        int orderCounter = 0;
        if (!sharedDir_.empty()) {
            loadFileIntoEntries(sharedDir_ + "/rime_ice_full.tsv", orderCounter);
            loadFileIntoEntries(sharedDir_ + "/rime_ice_compact.tsv", orderCounter);

            DIR* dir = opendir(sharedDir_.c_str());
            if (dir != nullptr) {
                struct dirent* ent = nullptr;
                while ((ent = readdir(dir)) != nullptr) {
                    std::string name(ent->d_name);
                    if (name == "." || name == ".." ||
                        name == "rime_ice_compact.tsv" ||
                        name == "rime_ice_full.tsv") {
                        continue;
                    }
                    if (name.size() > 10 && name.substr(name.size() - 10) == ".dict.yaml") {
                        loadFileIntoEntries(sharedDir_ + "/" + name, orderCounter);
                    } else if (name.size() > 4 &&
                               (name.substr(name.size() - 4) == ".tsv" ||
                                name.substr(name.size() - 4) == ".txt")) {
                        loadFileIntoEntries(sharedDir_ + "/" + name, orderCounter);
                    }
                }
                closedir(dir);
            }
        }

        for (size_t i = 0; i < entries_.size(); ++i) {
            const auto& e = entries_[i];
            byExactPinyin_[e.pinyin].push_back(i);
            byCompactPinyin_[e.compactPinyin].push_back(i);
            byInitials_[e.initials].push_back(i);
            size_t sp = e.pinyin.find(' ');
            std::string firstSyl = (sp == std::string::npos) ? e.pinyin : e.pinyin.substr(0, sp);
            byFirstSyllable_[firstSyl].push_back(i);
        }
    }

    std::string userDbPathLocked() const {
        if (userDir_.empty()) return "";
        return userDir_ + "/rime_ice.userdb.tsv";
    }

    void loadUserDbLocked() {
        userDb_.clear();
        std::string path = userDbPathLocked();
        if (path.empty()) return;
        std::ifstream in(path);
        if (!in.is_open()) return;
        std::string line;
        while (std::getline(in, line)) {
            if (!line.empty() && line.back() == '\r') line.pop_back();
            if (line.empty() || line[0] == '#') continue;
            size_t t1 = line.find('\t');
            if (t1 == std::string::npos) continue;
            size_t t2 = line.find('\t', t1 + 1);
            if (t2 == std::string::npos) continue;
            size_t t3 = line.find('\t', t2 + 1);
            UserDbRecord rec;
            rec.pinyin = normalizePinyin(line.substr(0, t1));
            rec.word = line.substr(t1 + 1, t2 - t1 - 1);
            rec.commits = std::strtoll(line.substr(t2 + 1, t3 == std::string::npos ? std::string::npos : t3 - t2 - 1).c_str(), nullptr, 10);
            rec.lastTick = (t3 == std::string::npos) ? 1 : std::strtoll(line.substr(t3 + 1).c_str(), nullptr, 10);
            if (!rec.pinyin.empty() && !rec.word.empty()) {
                userDb_[rec.pinyin + "\t" + rec.word] = rec;
                if (rec.lastTick > userTick_) userTick_ = rec.lastTick;
            }
        }
    }

    void saveUserDbLocked() {
        if (incognito_ || !enableUserDict_ || userDir_.empty()) return;
        std::string path = userDbPathLocked();
        if (path.empty()) return;
        std::ofstream out(path, std::ios::trunc);
        if (!out.is_open()) return;
        out << "# Rime userdb v1\n";
        for (const auto& kv : userDb_) {
            const auto& r = kv.second;
            out << r.pinyin << "\t" << r.word << "\t" << r.commits << "\t" << r.lastTick << "\n";
        }
    }

    void recordUserCommitLocked(const std::string& pinyin, const std::string& word) {
        if (incognito_ || !enableUserDict_ || fixedOrder_) return;
        std::string normPy = normalizePinyin(pinyin);
        if (normPy.empty() || word.empty()) return;
        std::string key = normPy + "\t" + word;
        userTick_++;
        auto& rec = userDb_[key];
        rec.pinyin = normPy;
        rec.word = word;
        rec.commits += 1;
        rec.lastTick = userTick_;
        saveUserDbLocked();
    }

    static std::vector<std::string> segmentFullPinyin(const std::string& raw) {
        std::vector<std::string> syllables;
        const auto& valid = validPinyinSyllables();
        size_t n = raw.size();
        size_t i = 0;
        while (i < n) {
            if (raw[i] == '\'') {
                i++;
                continue;
            }
            size_t matchedLen = 0;
            size_t maxL = std::min<size_t>(6, n - i);
            for (size_t len = maxL; len >= 1; --len) {
                if (raw.substr(i, len).find('\'') != std::string::npos) continue;
                std::string sub = raw.substr(i, len);
                if (valid.find(sub) != valid.end()) {
                    // Check vowel boundary ambiguity (e.g. "nihao" -> "ni" + "hao", "shijian" -> "shi" + "jian", "xian" -> "xian")
                    if (len > 1 && i + len < n) {
                        char lastCh = sub.back();
                        char nextCh = raw[i + len];
                        if ((lastCh == 'n' || lastCh == 'g' || lastCh == 'r') &&
                            (nextCh == 'a' || nextCh == 'e' || nextCh == 'i' || nextCh == 'o' || nextCh == 'u' || nextCh == 'v')) {
                            std::string shorter = sub.substr(0, len - 1);
                            if (valid.find(shorter) != valid.end()) {
                                // Check if next starts a valid syllable when split one char earlier
                                size_t rem = n - (i + len - 1);
                                bool betterSplit = false;
                                for (size_t nl = std::min<size_t>(6, rem); nl >= 2; --nl) {
                                    if (valid.find(raw.substr(i + len - 1, nl)) != valid.end()) {
                                        betterSplit = true;
                                        break;
                                    }
                                }
                                if (betterSplit) continue;
                            }
                        }
                    }
                    matchedLen = len;
                    break;
                }
            }
            if (matchedLen > 0) {
                syllables.push_back(raw.substr(i, matchedLen));
                i += matchedLen;
            } else {
                // Single consonant initial or trailing partial
                if (i + 1 < n && ((raw[i] == 'z' || raw[i] == 'c' || raw[i] == 's') && raw[i + 1] == 'h')) {
                    syllables.push_back(raw.substr(i, 2));
                    i += 2;
                } else {
                    syllables.push_back(raw.substr(i, 1));
                    i += 1;
                }
            }
        }
        return syllables;
    }

    static std::vector<std::string> segmentDoublePinyinFlypy(const std::string& raw) {
        std::vector<std::string> syllables;
        std::string clean;
        for (char c : raw) {
            if (c >= 'a' && c <= 'z') clean.push_back(c);
        }
        size_t i = 0;
        while (i + 1 < clean.size()) {
            syllables.push_back(mapFlypyDoublePinyinSyllable(clean[i], clean[i + 1]));
            i += 2;
        }
        if (i < clean.size()) {
            char rem = clean[i];
            if (rem == 'v') syllables.push_back("zh");
            else if (rem == 'i') syllables.push_back("ch");
            else if (rem == 'u') syllables.push_back("sh");
            else syllables.push_back(std::string(1, rem));
        }
        return syllables;
    }

    std::string bestWordForSyllablesLocked(const std::vector<std::string>& syls, size_t start, size_t len) const {
        std::string joined;
        std::string initials;
        for (size_t k = 0; k < len; ++k) {
            if (k > 0) joined.push_back(' ');
            joined += syls[start + k];
            if (!syls[start + k].empty()) initials.push_back(syls[start + k][0]);
        }
        std::string bestWord;
        int64_t bestScore = -1;

        if (!fixedOrder_ && enableUserDict_) {
            for (const auto& kv : userDb_) {
                if (kv.second.pinyin == joined) {
                    int64_t sc = 1000000LL + kv.second.commits * 5000LL + kv.second.lastTick;
                    if (sc > bestScore) {
                        bestScore = sc;
                        bestWord = kv.second.word;
                    }
                }
            }
        }

        auto it = byExactPinyin_.find(joined);
        if (it != byExactPinyin_.end()) {
            for (size_t idx : it->second) {
                const auto& e = entries_[idx];
                int64_t sc = e.baseWeight;
                if (sc > bestScore) {
                    bestScore = sc;
                    bestWord = e.word;
                }
            }
        }

        if (bestWord.empty() && len == 1 && !syls[start].empty()) {
            auto initIt = byInitials_.find(syls[start].substr(0, 1));
            if (initIt != byInitials_.end()) {
                for (size_t idx : initIt->second) {
                    const auto& e = entries_[idx];
                    if (e.syllableCount == 1 && e.pinyin.find(syls[start]) == 0 && e.baseWeight > bestScore) {
                        bestScore = e.baseWeight;
                        bestWord = e.word;
                    }
                }
            }
        }
        return bestWord;
    }

    void recomputeCandidatesLocked() {
        candidates_.clear();
        preedit_.clear();
        if (rawInput_.empty()) return;

        bool isDoublePinyin = (schemaId_ == "double_pinyin_flypy");
        std::vector<std::string> syllables = isDoublePinyin
            ? segmentDoublePinyinFlypy(rawInput_)
            : segmentFullPinyin(rawInput_);

        for (size_t i = 0; i < syllables.size(); ++i) {
            if (i > 0) preedit_.push_back('\'');
            preedit_ += syllables[i];
        }

        std::string fullSpaced;
        std::string fullCompact;
        std::string fullInitials;
        for (size_t i = 0; i < syllables.size(); ++i) {
            if (i > 0) fullSpaced.push_back(' ');
            fullSpaced += syllables[i];
            fullCompact += syllables[i];
            if (!syllables[i].empty()) fullInitials.push_back(syllables[i][0]);
        }

        std::unordered_set<std::string> seenWords;
        auto addCand = [&](const std::string& text, const std::string& py, int64_t score, int orderIdx) {
            if (text.empty() || seenWords.find(text) != seenWords.end()) return;
            seenWords.insert(text);
            CandidateItem item;
            item.text = text;
            item.pinyin = py;
            item.score = score;
            item.orderIndex = orderIdx;
            candidates_.push_back(std::move(item));
        };

        // 1. UserDB exact matches (when dynamic frequency is active)
        if (!fixedOrder_ && enableUserDict_) {
            std::vector<UserDbRecord> userMatches;
            for (const auto& kv : userDb_) {
                if (kv.second.pinyin == fullSpaced) {
                    userMatches.push_back(kv.second);
                }
            }
            std::sort(userMatches.begin(), userMatches.end(), [](const UserDbRecord& a, const UserDbRecord& b) {
                if (a.commits != b.commits) return a.commits > b.commits;
                return a.lastTick > b.lastTick;
            });
            for (const auto& u : userMatches) {
                addCand(u.word, u.pinyin, 2000000LL + u.commits * 10000LL + u.lastTick, -1000);
            }
        }

        // 2. Exact full-pinyin dictionary matches
        std::vector<CandidateItem> exactList;
        auto appendEntryCandidate = [&](const DictEntry& e, int64_t bonus) {
            int64_t userBoost = 0;
            if (!fixedOrder_ && enableUserDict_) {
                auto uIt = userDb_.find(e.pinyin + "\t" + e.word);
                if (uIt != userDb_.end()) {
                    userBoost = 1500000LL + uIt->second.commits * 10000LL + uIt->second.lastTick;
                }
            }
            CandidateItem c;
            c.text = e.word;
            c.pinyin = e.pinyin;
            c.score = e.baseWeight + bonus + userBoost;
            c.orderIndex = e.orderIndex;
            exactList.push_back(std::move(c));
        };

        auto exIt = byExactPinyin_.find(fullSpaced);
        if (exIt != byExactPinyin_.end()) {
            for (size_t idx : exIt->second) {
                appendEntryCandidate(entries_[idx], 500000LL);
            }
        }

        if (!isDoublePinyin) {
            std::string rawClean;
            for (char c : rawInput_) if (c >= 'a' && c <= 'z') rawClean.push_back(c);
            auto cpIt = byCompactPinyin_.find(rawClean);
            if (cpIt != byCompactPinyin_.end()) {
                for (size_t idx : cpIt->second) {
                    appendEntryCandidate(entries_[idx], 450000LL);
                }
            }
            auto inIt = byInitials_.find(rawClean);
            if (inIt != byInitials_.end()) {
                for (size_t idx : inIt->second) {
                    appendEntryCandidate(entries_[idx], 200000LL);
                }
            }
        } else {
            auto inIt = byInitials_.find(fullInitials);
            if (inIt != byInitials_.end()) {
                for (size_t idx : inIt->second) {
                    const auto& e = entries_[idx];
                    if (e.syllableCount == static_cast<int>(syllables.size())) {
                        // Check if each syllable starts with syllables[k]
                        std::stringstream ss(e.pinyin);
                        std::string tok;
                        size_t k = 0;
                        bool ok = true;
                        while (std::getline(ss, tok, ' ')) {
                            if (k >= syllables.size() || tok.find(syllables[k]) != 0) {
                                ok = false;
                                break;
                            }
                            k++;
                        }
                        if (ok && k == syllables.size()) {
                            appendEntryCandidate(e, 350000LL);
                        }
                    }
                }
            }
        }

        if (fixedOrder_) {
            std::sort(exactList.begin(), exactList.end(), [](const CandidateItem& a, const CandidateItem& b) {
                return a.orderIndex < b.orderIndex;
            });
        } else {
            std::sort(exactList.begin(), exactList.end(), [](const CandidateItem& a, const CandidateItem& b) {
                if (a.score != b.score) return a.score > b.score;
                return a.orderIndex < b.orderIndex;
            });
        }

        for (const auto& c : exactList) {
            addCand(c.text, c.pinyin, c.score, c.orderIndex);
        }

        // 3. Whole-sentence greedy composition when >= 2 syllables
        if (syllables.size() >= 2) {
            std::string composed;
            size_t pos = 0;
            bool allMatched = true;
            while (pos < syllables.size()) {
                size_t rem = syllables.size() - pos;
                std::string piece;
                size_t step = 0;
                for (size_t l = std::min<size_t>(4, rem); l >= 1; --l) {
                    piece = bestWordForSyllablesLocked(syllables, pos, l);
                    if (!piece.empty()) {
                        step = l;
                        break;
                    }
                }
                if (step == 0) {
                    allMatched = false;
                    break;
                }
                composed += piece;
                pos += step;
            }
            if (allMatched && !composed.empty()) {
                // Insert composed sentence right after exact matches (or at top if no exact full match)
                addCand(composed, fullSpaced, 400000LL, 0);
            }
        }

        // 4. Prefix & first-syllable single-character candidates
        if (!syllables.empty()) {
            for (size_t l = syllables.size() - 1; l >= 1; --l) {
                std::string subPy;
                for (size_t k = 0; k < l; ++k) {
                    if (k > 0) subPy.push_back(' ');
                    subPy += syllables[k];
                }
                auto subIt = byExactPinyin_.find(subPy);
                if (subIt != byExactPinyin_.end()) {
                    for (size_t idx : subIt->second) {
                        const auto& e = entries_[idx];
                        addCand(e.word, e.pinyin, e.baseWeight, e.orderIndex);
                    }
                }
                if (l == 1) break;
            }
            // Also prefix matches on first syllable
            const std::string& firstSyl = syllables[0];
            for (const auto& kv : byFirstSyllable_) {
                if (kv.first.find(firstSyl) == 0) {
                    for (size_t idx : kv.second) {
                        const auto& e = entries_[idx];
                        if (e.syllableCount == 1) {
                            addCand(e.word, e.pinyin, e.baseWeight / 2, e.orderIndex);
                        }
                    }
                }
            }
        }
    }

    std::string commitCandidateLocked(int index) {
        if (index < 0 || index >= static_cast<int>(candidates_.size())) return "";
        CandidateItem picked = candidates_[index];
        recordUserCommitLocked(picked.pinyin, picked.text);
        pendingCommit_ = picked.text;
        clearLocked();
        return picked.text;
    }

    std::mutex mutex_;
    bool initialized_ = false;
    std::string sharedDir_;
    std::string userDir_;
    std::string schemaId_ = "rime_ice";
    bool incognito_ = false;
    bool fixedOrder_ = false;
    bool enableUserDict_ = true;
    bool asciiMode_ = false;
    int64_t userTick_ = 0;
    std::unordered_map<std::string, bool> options_;

    std::vector<DictEntry> entries_;
    std::unordered_map<std::string, std::vector<size_t>> byExactPinyin_;
    std::unordered_map<std::string, std::vector<size_t>> byCompactPinyin_;
    std::unordered_map<std::string, std::vector<size_t>> byInitials_;
    std::unordered_map<std::string, std::vector<size_t>> byFirstSyllable_;
    std::unordered_map<std::string, UserDbRecord> userDb_;

    std::string rawInput_;
    std::string preedit_;
    std::vector<CandidateItem> candidates_;
    std::string pendingCommit_;
};

// Global state for dynamic librime.so bridge + fallback C++ engine
static void* g_librimeHandle = nullptr;
static RimeApi* g_rimeApi = nullptr;
static RimeSessionId g_sessionId = 0;
static bool g_traitsSetupDone = false;
static NativeRimeFallbackEngine g_fallbackEngine;

static void tryBindExternalLibrime() {
    if (g_rimeApi != nullptr) return;
    g_librimeHandle = dlopen("librime.so", RTLD_NOW | RTLD_LOCAL);
    if (g_librimeHandle != nullptr) {
        auto getApi = reinterpret_cast<rime_get_api_func>(dlsym(g_librimeHandle, "rime_get_api"));
        if (getApi != nullptr) {
            g_rimeApi = getApi();
            LOGI("Bound external librime.so successfully via rime_get_api()");
        }
    }
}

static std::string jstringToStdString(JNIEnv* env, jstring jstr) {
    if (jstr == nullptr) return "";
    const char* chars = env->GetStringUTFChars(jstr, nullptr);
    if (chars == nullptr) return "";
    std::string out(chars);
    env->ReleaseStringUTFChars(jstr, chars);
    return out;
}

} // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_typefree_rime_RimeBridge_nativeInitialize(
    JNIEnv* env,
    jobject /*thiz*/,
    jstring sharedDir,
    jstring userDir
) {
    std::string sDir = jstringToStdString(env, sharedDir);
    std::string uDir = jstringToStdString(env, userDir);

    tryBindExternalLibrime();
    if (g_rimeApi != nullptr) {
        RimeTraits traits{};
        RIME_STRUCT_INIT(RimeTraits, traits);
        traits.shared_data_dir = sDir.c_str();
        traits.user_data_dir = uDir.c_str();
        traits.distribution_name = "TypeFree-Rime";
        traits.distribution_code_name = "TypeFree";
        traits.distribution_version = "2.7.0";
        traits.app_name = "rime.typefree";
        if (!g_traitsSetupDone && g_rimeApi->setup != nullptr) {
            g_rimeApi->setup(&traits);
            g_traitsSetupDone = true;
        }
        if (g_rimeApi->initialize != nullptr) {
            g_rimeApi->initialize(&traits);
        }
        if (g_rimeApi->start_maintenance != nullptr) {
            g_rimeApi->start_maintenance(False);
        }
        if (g_rimeApi->create_session != nullptr) {
            g_sessionId = g_rimeApi->create_session();
        }
    }

    bool ok = g_fallbackEngine.initialize(sDir, uDir);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_typefree_rime_RimeBridge_nativeDeploy(
    JNIEnv* env,
    jobject /*thiz*/,
    jstring schemaId
) {
    std::string schema = jstringToStdString(env, schemaId);
    if (g_rimeApi != nullptr && g_rimeApi->deploy != nullptr) {
        g_rimeApi->deploy();
        if (g_sessionId != 0 && !schema.empty() && g_rimeApi->select_schema != nullptr) {
            g_rimeApi->select_schema(g_sessionId, schema.c_str());
        }
    }
    bool ok = g_fallbackEngine.deploy(schema);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_typefree_rime_RimeBridge_nativeSelectSchema(
    JNIEnv* env,
    jobject /*thiz*/,
    jstring schemaId
) {
    std::string schema = jstringToStdString(env, schemaId);
    if (g_rimeApi != nullptr && g_sessionId != 0 && g_rimeApi->select_schema != nullptr) {
        g_rimeApi->select_schema(g_sessionId, schema.c_str());
    }
    bool ok = g_fallbackEngine.selectSchema(schema);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_typefree_rime_RimeBridge_nativeProcessKey(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jint keyCode,
    jint mask
) {
    if (g_rimeApi != nullptr && g_sessionId != 0 && g_rimeApi->process_key != nullptr) {
        g_rimeApi->process_key(g_sessionId, keyCode, mask);
    }
    bool ok = g_fallbackEngine.processKey(keyCode, mask);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_typefree_rime_RimeBridge_nativeProcessKeyText(
    JNIEnv* env,
    jobject /*thiz*/,
    jstring keys
) {
    std::string k = jstringToStdString(env, keys);
    if (g_rimeApi != nullptr && g_sessionId != 0 && g_rimeApi->simulate_key_sequence != nullptr) {
        g_rimeApi->clear_composition(g_sessionId);
        g_rimeApi->simulate_key_sequence(g_sessionId, k.c_str());
    }
    g_fallbackEngine.processKeyText(k);
}

JNIEXPORT jstring JNICALL
Java_com_typefree_rime_RimeBridge_nativeGetPreedit(
    JNIEnv* env,
    jobject /*thiz*/
) {
    return env->NewStringUTF(g_fallbackEngine.preedit().c_str());
}

JNIEXPORT jobjectArray JNICALL
Java_com_typefree_rime_RimeBridge_nativeGetCandidatesFlat(
    JNIEnv* env,
    jobject /*thiz*/,
    jint maxCount
) {
    const auto& cands = g_fallbackEngine.candidates();
    size_t limit = maxCount > 0 ? std::min<size_t>(cands.size(), static_cast<size_t>(maxCount)) : cands.size();
    jclass strCls = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray(static_cast<jsize>(limit * 2), strCls, env->NewStringUTF(""));
    for (size_t i = 0; i < limit; ++i) {
        env->SetObjectArrayElement(arr, static_cast<jsize>(i * 2), env->NewStringUTF(cands[i].text.c_str()));
        env->SetObjectArrayElement(arr, static_cast<jsize>(i * 2 + 1), env->NewStringUTF(cands[i].comment.c_str()));
    }
    return arr;
}

JNIEXPORT jstring JNICALL
Java_com_typefree_rime_RimeBridge_nativeSelectCandidate(
    JNIEnv* env,
    jobject /*thiz*/,
    jint index
) {
    std::string committed = g_fallbackEngine.selectCandidate(index);
    return env->NewStringUTF(committed.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_typefree_rime_RimeBridge_nativeGetCommit(
    JNIEnv* env,
    jobject /*thiz*/
) {
    std::string committed = g_fallbackEngine.getCommit();
    return env->NewStringUTF(committed.c_str());
}

JNIEXPORT void JNICALL
Java_com_typefree_rime_RimeBridge_nativeClearComposition(
    JNIEnv* /*env*/,
    jobject /*thiz*/
) {
    if (g_rimeApi != nullptr && g_sessionId != 0 && g_rimeApi->clear_composition != nullptr) {
        g_rimeApi->clear_composition(g_sessionId);
    }
    g_fallbackEngine.clearComposition();
}

JNIEXPORT void JNICALL
Java_com_typefree_rime_RimeBridge_nativeSetOption(
    JNIEnv* env,
    jobject /*thiz*/,
    jstring option,
    jboolean value
) {
    std::string opt = jstringToStdString(env, option);
    if (g_rimeApi != nullptr && g_sessionId != 0 && g_rimeApi->set_option != nullptr) {
        g_rimeApi->set_option(g_sessionId, opt.c_str(), value ? True : False);
    }
    g_fallbackEngine.setOption(opt, value == JNI_TRUE);
}

JNIEXPORT jboolean JNICALL
Java_com_typefree_rime_RimeBridge_nativeGetOption(
    JNIEnv* env,
    jobject /*thiz*/,
    jstring option
) {
    std::string opt = jstringToStdString(env, option);
    return g_fallbackEngine.getOption(opt) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_typefree_rime_RimeBridge_nativeLearnWord(
    JNIEnv* env,
    jobject /*thiz*/,
    jstring pinyinKey,
    jstring word
) {
    std::string py = jstringToStdString(env, pinyinKey);
    std::string w = jstringToStdString(env, word);
    return g_fallbackEngine.learnWord(py, w) ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
