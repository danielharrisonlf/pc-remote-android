package com.pcremote.validation;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.widget.TextView;

/** Foreground inspection for a disposable emulator only. Exposes sample equality,
 * sensitivity and bounds, never the clipboard contents or exception text. */
public final class ClipboardInspection extends Activity {
    private TextView status;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        status = new TextView(this);
        status.setTextSize(22);
        status.setPadding(32,96,32,32);
        status.setText("Waiting for focus");
        setContentView(status);
    }
    @Override public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (!focus || status == null) return;
        try {
            ClipboardManager clipboard = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
            ClipDescription description = clipboard.getPrimaryClipDescription();
            boolean sensitive = description != null && description.getExtras() != null &&
                description.getExtras().getBoolean("android.content.extra.IS_SENSITIVE",false);
            ClipData clip = sensitive ? null : clipboard.getPrimaryClip();
            CharSequence value = clip == null || clip.getItemCount() == 0 ? null : clip.getItemAt(0).getText();
            String text = value == null ? "" : value.toString();
            status.setText("unicode=" + "Olá, ação 👋\nhttps://example.org\n漢字".equals(text) +
                ";second=" + "Outra cópia — café 🎵".equals(text) +
                ";baseline=" + "PC Remote background baseline sample".equals(text) +
                ";sensitive=" + sensitive +
                ";over_limit=" + (text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65536));
        } catch (RuntimeException error) { status.setText("inspection_unavailable"); }
    }
}
