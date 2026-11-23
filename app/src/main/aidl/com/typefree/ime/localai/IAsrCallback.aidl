package com.typefree.ime.localai;

oneway interface IAsrCallback {
    void onPartial(long sid, String finalizedText, String interimText, long generation);
    void onFinal(long sid, String fullText, long generation, long latencyMs);
    void onError(long sid, int errorCode, String message, long generation);
}
