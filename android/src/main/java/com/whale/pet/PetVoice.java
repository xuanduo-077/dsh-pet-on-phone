package com.whale.pet;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;
import java.util.Locale;

/**
 * 系统语音识别封装（按住说话模式）。
 * 生命周期：start() 开始听 → onPartial/onFinal 回调 → stop() 释放。
 * 回调全部在主线程。识别用的是系统 Google/厂商引擎，离线可用性看机型。
 */
public final class PetVoice {
    public interface Listener {
        /** 音量 0~1，用于让鲸鱼有反应（可选实现） */
        default void onVolume(float v) {}
        /** 中间结果（边说边出字） */
        default void onPartial(String text) {}
        /** 最终结果（一句话说完） */
        void onFinal(String text);
        /** 出错（没权限/没引擎/网络问题），message 可直接展示 */
        void onError(String message);
    }

    private final SpeechRecognizer recognizer;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Listener listener;
    private boolean listening;

    public PetVoice(Context context) {
        recognizer = SpeechRecognizer.createSpeechRecognizer(context);
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) {}
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rmsdB) { if (listener != null) listener.onVolume(Math.max(0f, Math.min(1f, (rmsdB + 2f) / 12f))); }
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() {}
            @Override public void onError(int error) {
                listening = false;
                String msg;
                switch (error) {
                    case SpeechRecognizer.ERROR_NO_MATCH:
                    case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: msg = "没听清你说什么，再试一次？"; break;
                    case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: msg = ""; break;
                    case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: msg = "识别忙不过来，稍等再试"; break;
                    default: msg = "识别出错了（代码 " + error + "）";
                }
                final String m = msg;
                main.post(() -> { if (listener != null) listener.onError(m); });
            }
            @Override public void onResults(Bundle results) {
                listening = false;
                ArrayList<String> list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                final String text = list != null && !list.isEmpty() ? list.get(0) : "";
                main.post(() -> { if (listener != null) listener.onFinal(text); });
            }
            @Override public void onPartialResults(Bundle partialResults) {
                ArrayList<String> list = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (list != null && !list.isEmpty()) {
                    final String t = list.get(0);
                    main.post(() -> { if (listener != null) listener.onPartial(t); });
                }
            }
            @Override public void onEvent(int eventType, Bundle params) {}
        });
    }

    public boolean isAvailable(Context c) { return SpeechRecognizer.isRecognitionAvailable(c); }
    public boolean isListening() { return listening; }

    /** 开始听（按住期间） */
    public void start(Listener l) {
        if (listening || recognizer == null) return;
        listener = l;
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN");
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        try {
            recognizer.startListening(intent);
            listening = true;
        } catch (Exception e) {
            main.post(() -> { if (listener != null) listener.onError("无法启动语音识别：" + e.getMessage()); });
        }
    }

    /** 结束听（松手），引擎会回调 onFinal */
    public void stop() {
        if (recognizer != null && listening) recognizer.stopListening();
    }

    public void destroy() {
        if (recognizer != null) { try { recognizer.destroy(); } catch (Exception ignored) {} }
        listening = false;
    }
}
