package com.tencent.mm.plugin.voip.ui;

public class VideoActivity extends android.app.Activity {
    @Override public void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        android.widget.TextView text = new android.widget.TextView(this);
        text.setText("SIMULATED CALL WINDOW\nNot a real WeChat call. No audio or network.");
        text.setTextSize(24); text.setPadding(32, 220, 32, 32); setContentView(text);
    }
}
