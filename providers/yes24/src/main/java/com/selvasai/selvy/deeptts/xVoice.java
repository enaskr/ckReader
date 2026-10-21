package com.selvasai.selvy.deeptts;

import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.Queue;

/** JNI entry points and callbacks used by the original YES24 Selvy engine. */
public final class xVoice {
    public static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Queue<CallbackItem> curRequestIds = new LinkedList<>();
    private static volatile OnxVoiceStatusListener g_listener;
    private static final AudioTrack.OnPlaybackPositionUpdateListener mAudioTracklistener =
        new AudioTrack.OnPlaybackPositionUpdateListener() {
            @Override public void onMarkerReached(AudioTrack track) {
                CallbackItem item = curRequestIds.poll();
                if (item != null && g_listener != null) g_listener.onPlayList(item.id);
                while ((item = curRequestIds.peek()) != null && item.empty) {
                    curRequestIds.poll();
                    if (g_listener != null) g_listener.onPlayList(item.id);
                }
            }
            @Override public void onPeriodicNotification(AudioTrack track) {}
        };
    private static final class CallbackItem {
        final long id; final boolean empty;
        CallbackItem(long id, boolean empty) { this.id=id; this.empty=empty; }
    }
    static {
        System.loadLibrary("selvyTTS-JNI");
    }

    public void setOnxVoiceStatusListener(OnxVoiceStatusListener listener) { g_listener=listener; }
    private static byte[] GetSystemID() {
        try {
            for (NetworkInterface net : Collections.list(NetworkInterface.getNetworkInterfaces()))
                if ("wlan0".equalsIgnoreCase(net.getName())) return net.getHardwareAddress();
        } catch (Exception ignored) {}
        return null;
    }
    private static void TTSStartCallBack() { curRequestIds.clear(); if(g_listener!=null)g_listener.onStart(); }
    private static void PlayDoneCallBack() { curRequestIds.clear(); if(g_listener!=null)g_listener.onFinish(); }
    private static void TTSCancelCallBack() { if(g_listener!=null)g_listener.onStop(); }
    private static void TTSErrorCallBack(int code) { if(g_listener!=null)g_listener.onError(code); }
    private static void TTSPauseCallBack() { if(g_listener!=null)g_listener.onPause(); }
    private static void TTSResumeCallBack() { if(g_listener!=null)g_listener.onContinue(); }
    private static void JavaTTSCallBack(int type, short[] samples, short count, long id) {
        if(g_listener!=null)g_listener.onSynthesize(type,samples,count,id);
    }
    private static void PlayListCallBack(long id, boolean empty) {
        if(curRequestIds.isEmpty()) { if(g_listener!=null)g_listener.onPlayList(id); }
        else curRequestIds.offer(new CallbackItem(id,empty));
    }
    private static void FileStartCallBack(String path) { if(g_listener!=null)g_listener.onFileStart(path); }
    private static void FileEndCallBack(String path) { if(g_listener!=null)g_listener.onFileEnd(path); }

    public native int PTTS_Initialize();
    public native void PTTS_UnInitialize();
    public native int PTTS_LoadEngine(int language, String directory, int option);
    public native int PTTS_LoadEngineEx(int first, int second, String directory, int option);
    public native int PTTS_UnLoadEngine(int language);
    public native int PTTS_UnLoadEngineEx(int first, int second);
    public native int PTTS_PlayTTS(String text, String extra, int language, int speaker);
    public native int PTTS_PlayTTSList(ArrayList<PlayTTSListItem> items, int language, int speaker);
    public native int PTTS_PlayTTSListInsert(PlayTTSListItem item, int language, int speaker);
    public native int PTTS_TextToBuffer(String text, String extra, int language, int speaker, int option);
    public native int PTTS_TextToBufferWithId(String text, String extra, int language, int speaker, int option, long id);
    public native int PTTS_TextToFile(int a, int b, int c, int d, int e, String f, String g, String h, String i);
    public native int PTTS_RegisterUserDict(int language, String path);
    public native String PTTS_GetRegisterUserDict(int language);
    public native int PTTS_DeleteUserDict(int language);
    public native int PTTS_GetMaxChannel(int first, int second);
    public native int PTTS_StopTTS();
    public native int PTTS_PauseTTS();
    public native int PTTS_ResumeTTS();
    public native int PTTS_SetSpeed(int speed);
    public native int PTTS_SetPitch(int pitch);
    public native int PTTS_SetVolume(int volume);
    public native int PTTS_SetReadingBracket(boolean enabled);
    public native int PTTS_SetSkipHanja(boolean enabled);
    public native int PTTS_GetPlaybackStatus();
}
