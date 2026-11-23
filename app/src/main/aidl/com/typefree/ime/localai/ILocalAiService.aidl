package com.typefree.ime.localai;

import android.os.ParcelFileDescriptor;
import com.typefree.ime.localai.AsrParams;
import com.typefree.ime.localai.GenRequest;
import com.typefree.ime.localai.IAsrCallback;
import com.typefree.ime.localai.ITokenCallback;
import com.typefree.ime.localai.RuntimeMetrics;

interface ILocalAiService {
    int protocolVersion();
    oneway void openAsrSession(long sid, in ParcelFileDescriptor pcmPipe, in AsrParams params, IAsrCallback cb);
    oneway void transcribeFile(long rid, String wavPath, in AsrParams params, IAsrCallback cb);
    oneway void generate(long rid, in GenRequest req, ITokenCallback cb);
    oneway void cancel(long rid);
    oneway void prewarmSlot(int slot, String modelId, String modelDirPath);
    oneway void unloadSlot(int slot);
    oneway void unloadAll();
    String probeModel(int slot, String modelId, String modelDirPath);
    RuntimeMetrics getRuntimeMetrics();
}
