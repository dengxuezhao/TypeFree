package com.typefree.ime.localai;

oneway interface ITokenCallback {
    void onTokenBatch(long rid, String tokenChunk, int tokenCount, long generation);
    void onCompleted(long rid, String fullText, long generation, long ttftMs, long totalMs, int totalTokens);
    void onError(long rid, int errorCode, String message, long generation);
}
