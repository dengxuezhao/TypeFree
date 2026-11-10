#!/usr/bin/env bash
set -euo pipefail

CPP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$CPP_DIR"

fetch_github_tarball() {
    local repo="$1"
    local sha="$2"
    local dest="$3"
    if [ -f "$dest/CMakeLists.txt" ] || [ -d "$dest/lua5.4" ]; then
        echo "[skip] $dest already populated"
        return 0
    fi
    echo "[fetch] $repo @ $sha -> $dest"
    rm -rf "$dest"
    mkdir -p "$dest"
    curl -sL "https://codeload.github.com/${repo}/tar.gz/${sha}" | tar -xz -C "$dest" --strip-components=1
}

fetch_github_tarball "rime/librime" "56737cd29a4d393a104a2098ece3697aa2810465" "$CPP_DIR/librime"
fetch_github_tarball "google/glog" "7b134a5c82c0c0b5698bb6bf7a835b230c5638e4" "$CPP_DIR/librime/deps/glog"
fetch_github_tarball "google/leveldb" "bb74ef739973a70ca9f0d90788a1da89b788c0ad" "$CPP_DIR/librime/deps/leveldb"
fetch_github_tarball "jbeder/yaml-cpp" "56e3bb550c91fd7005566f19c079cb7a503223cf" "$CPP_DIR/librime/deps/yaml-cpp"
fetch_github_tarball "s-yata/marisa-trie" "3e87d53b78e15f2f43783d5e376561a8c9722051" "$CPP_DIR/librime/deps/marisa-trie"
fetch_github_tarball "BYVoid/OpenCC" "025f371dc76b598d77384fbdab90c937471844d8" "$CPP_DIR/librime/deps/opencc"
fetch_github_tarball "google/snappy" "9c28114a38866f6deeaa826db918293bc28ae410" "$CPP_DIR/snappy"
fetch_github_tarball "hchunhui/librime-lua" "6da5d6cedb853380ed1b5b9b6979cb1a9c028353" "$CPP_DIR/librime-lua"
fetch_github_tarball "hchunhui/librime-lua" "9e5bb71db1544913f8005dadc3df8439c00d08b7" "$CPP_DIR/librime-lua-deps"
fetch_github_tarball "lotem/librime-octagram" "bd12863f45fbbd5c7db06d5ec8be8987b10253bf" "$CPP_DIR/librime-octagram"
fetch_github_tarball "rime/librime-predict" "67c2881914a0242fc14ec7d6877782771b75800c" "$CPP_DIR/librime-predict"

BOOST_VER="1.92.0"
BOOST_TAR="$CPP_DIR/boost-${BOOST_VER}-cmake.tar.xz"
BOOST_SHA256="9bed76128d4e46755dbe818487788c6fceb6f72b378f4daa49b7e1e600d9088d"

if [ ! -f "$CPP_DIR/boost/CMakeLists.txt" ]; then
    echo "[fetch] Boost ${BOOST_VER} CMake release -> $CPP_DIR/boost"
    if [ ! -f "$BOOST_TAR" ]; then
        curl -L -o "$BOOST_TAR" "https://github.com/boostorg/boost/releases/download/boost-${BOOST_VER}/boost-${BOOST_VER}-cmake.tar.xz"
    fi
    ACTUAL_SHA="$(shasum -a 256 "$BOOST_TAR" | awk '{print $1}')"
    if [ "$ACTUAL_SHA" != "$BOOST_SHA256" ]; then
        echo "ERROR: Boost SHA256 mismatch: expected $BOOST_SHA256, got $ACTUAL_SHA" >&2
        rm -f "$BOOST_TAR"
        exit 1
    fi
    rm -rf "$CPP_DIR/boost" "$CPP_DIR/boost-${BOOST_VER}"
    tar -xJf "$BOOST_TAR" -C "$CPP_DIR"
    mv "$CPP_DIR/boost-${BOOST_VER}" "$CPP_DIR/boost"
    rm -f "$BOOST_TAR"
else
    echo "[skip] $CPP_DIR/boost already populated"
fi

mkdir -p "$CPP_DIR/librime/plugins"
for plugin in librime-lua librime-octagram librime-predict; do
    ln -sfn "$CPP_DIR/$plugin" "$CPP_DIR/librime/plugins/$plugin"
done
ln -sfn "$CPP_DIR/librime-lua-deps" "$CPP_DIR/librime/plugins/librime-lua/thirdparty"

# Patch Android cross-compile CLI tool subdirectories & runtime _incognito / fixed_candidate_order option checks
python3 - << 'PYEOF'
import os

cpp_dir = os.environ.get("CPP_DIR", ".")

def replace_once(path, old, new):
    if not os.path.exists(path):
        return
    with open(path, "r", encoding="utf-8") as f:
        content = f.read()
    if new in content:
        return
    if old in content:
        content = content.replace(old, new, 1)
        with open(path, "w", encoding="utf-8") as f:
            f.write(content)

# 1. Skip desktop CLI tools during Android NDK build
replace_once(
    os.path.join(cpp_dir, "librime/CMakeLists.txt"),
    "  add_subdirectory(tools)",
    "  if(NOT ANDROID)\n    add_subdirectory(tools)\n  endif()"
)
replace_once(
    os.path.join(cpp_dir, "librime-octagram/CMakeLists.txt"),
    "add_subdirectory(tools)",
    "if(NOT ANDROID)\n  add_subdirectory(tools)\nendif()"
)
replace_once(
    os.path.join(cpp_dir, "librime-predict/CMakeLists.txt"),
    "add_subdirectory(tools)",
    "if(NOT ANDROID)\n  add_subdirectory(tools)\nendif()"
)

# 2. Enforce runtime _incognito option check in Memory::OnCommit & Memory::OnUnhandledKey
replace_once(
    os.path.join(cpp_dir, "librime/src/rime/gear/memory.cc"),
    "void Memory::OnCommit(Context* ctx) {\n  if (!user_dict_ || user_dict_->readonly())\n    return;",
    "void Memory::OnCommit(Context* ctx) {\n  if (!user_dict_ || user_dict_->readonly() || (ctx && ctx->get_option(\"_incognito\")))\n    return;"
)
replace_once(
    os.path.join(cpp_dir, "librime/src/rime/gear/memory.cc"),
    "void Memory::OnUnhandledKey(Context* ctx, const KeyEvent& key) {\n  if (!user_dict_ || user_dict_->readonly())\n    return;",
    "void Memory::OnUnhandledKey(Context* ctx, const KeyEvent& key) {\n  if (!user_dict_ || user_dict_->readonly() || (ctx && ctx->get_option(\"_incognito\")))\n    return;"
)

# 3. Allow runtime fixed_candidate_order option to dynamically bypass user_dict lookup
replace_once(
    os.path.join(cpp_dir, "librime/src/rime/gear/script_translator.cc"),
    "  bool enable_user_dict =\n      user_dict_ && user_dict_->loaded() && !IsUserDictDisabledFor(input);",
    "  bool enable_user_dict =\n      user_dict_ && user_dict_->loaded() && !IsUserDictDisabledFor(input) &&\n      !(engine_ && engine_->context() && engine_->context()->get_option(\"fixed_candidate_order\"));"
)
PYEOF

echo "All upstream librime + plugins + dependencies fetched and verified!"
