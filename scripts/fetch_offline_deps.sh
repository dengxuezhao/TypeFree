#!/usr/bin/env bash
set -euo pipefail

# Fetches binary/third-party offline AI dependencies kept out of Git history:
# 1. sherpa-onnx static-link ONNX Runtime Android AAR (38 MB)
# 2. ggml-org/llama.cpp pinned commit for :localai-native JNI compilation

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

SHERPA_VERSION="2.0.2"
SHERPA_AAR_NAME="sherpa-onnx-static-link-onnxruntime-${SHERPA_VERSION}.aar"
SHERPA_AAR_PATH="${ROOT_DIR}/app/libs/${SHERPA_AAR_NAME}"
SHERPA_AAR_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v${SHERPA_VERSION}/${SHERPA_AAR_NAME}"
SHERPA_AAR_SHA256="cb73564809268c8b96bf98f1a32b0b3b611e01d028d22f10beb57ef55418c0cb"

LLAMA_CPP_DIR="${ROOT_DIR}/localai-native/src/main/cpp/llama.cpp"
LLAMA_CPP_REPO="https://github.com/ggml-org/llama.cpp.git"
LLAMA_CPP_COMMIT="b1b770d90d94cad9e9ea5b993bdbc383a522fb28"

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

mkdir -p "${ROOT_DIR}/app/libs"
if [ -f "${SHERPA_AAR_PATH}" ]; then
  ACTUAL_SHA="$(sha256_file "${SHERPA_AAR_PATH}")"
  if [ "${ACTUAL_SHA}" = "${SHERPA_AAR_SHA256}" ]; then
    echo "[fetch_offline_deps] sherpa-onnx AAR already present and verified: ${SHERPA_AAR_NAME}"
  else
    echo "[fetch_offline_deps] SHA-256 mismatch on cached AAR (${ACTUAL_SHA}), re-downloading..."
    rm -f "${SHERPA_AAR_PATH}"
  fi
fi

if [ ! -f "${SHERPA_AAR_PATH}" ]; then
  echo "[fetch_offline_deps] Downloading ${SHERPA_AAR_NAME}..."
  TMP_AAR="${SHERPA_AAR_PATH}.tmp"
  curl -fL --retry 3 --retry-delay 2 -o "${TMP_AAR}" "${SHERPA_AAR_URL}"
  ACTUAL_SHA="$(sha256_file "${TMP_AAR}")"
  if [ "${ACTUAL_SHA}" != "${SHERPA_AAR_SHA256}" ]; then
    rm -f "${TMP_AAR}"
    echo "[fetch_offline_deps] ERROR: SHA-256 mismatch! expected=${SHERPA_AAR_SHA256} actual=${ACTUAL_SHA}" >&2
    exit 1
  fi
  mv "${TMP_AAR}" "${SHERPA_AAR_PATH}"
  echo "[fetch_offline_deps] Verified ${SHERPA_AAR_NAME}"
fi

if [ -f "${LLAMA_CPP_DIR}/CMakeLists.txt" ]; then
  CUR_REV="$(git -C "${LLAMA_CPP_DIR}" rev-parse HEAD 2>/dev/null || true)"
  if [ "${CUR_REV}" = "${LLAMA_CPP_COMMIT}" ]; then
    echo "[fetch_offline_deps] llama.cpp already present at pinned commit ${LLAMA_CPP_COMMIT}"
  else
    echo "[fetch_offline_deps] llama.cpp commit mismatch (${CUR_REV:-unknown}), re-fetching ${LLAMA_CPP_COMMIT}..."
    rm -rf "${LLAMA_CPP_DIR}"
  fi
fi

if [ ! -f "${LLAMA_CPP_DIR}/CMakeLists.txt" ]; then
  echo "[fetch_offline_deps] Cloning llama.cpp at pinned commit ${LLAMA_CPP_COMMIT}..."
  mkdir -p "$(dirname "${LLAMA_CPP_DIR}")"
  git init "${LLAMA_CPP_DIR}"
  git -C "${LLAMA_CPP_DIR}" remote add origin "${LLAMA_CPP_REPO}"
  git -C "${LLAMA_CPP_DIR}" fetch --depth 1 origin "${LLAMA_CPP_COMMIT}"
  git -C "${LLAMA_CPP_DIR}" checkout --detach FETCH_HEAD
  echo "[fetch_offline_deps] Checked out llama.cpp at ${LLAMA_CPP_COMMIT}"
fi
