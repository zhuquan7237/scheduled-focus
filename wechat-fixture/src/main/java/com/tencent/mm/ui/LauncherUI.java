package com.tencent.mm.ui;

public class LauncherUI extends android.app.Activity {
    @Override public void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        android.widget.TextView text = new android.widget.TextView(this);
        text.setText("EMULATOR TEST FIXTURE\nNot WeChat. No login, chat or network.\nOrdinary foreground window");
        text.setTextSize(24); text.setPadding(32, 220, 32, 32); setContentView(text);
    }
}
