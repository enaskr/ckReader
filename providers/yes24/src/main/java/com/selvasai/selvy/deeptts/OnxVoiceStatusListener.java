package com.selvasai.selvy.deeptts;

/** Callbacks expected by the Selvy native AI speech library. */
public interface OnxVoiceStatusListener {
    void onStart();
    void onFinish();
    void onStop();
    void onPause();
    void onContinue();
    void onError(int code);
    void onSynthesize(int type, short[] samples, short count, long requestId);
    void onPlayList(long requestId);
    void onFileStart(String path);
    void onFileEnd(String path);
}
