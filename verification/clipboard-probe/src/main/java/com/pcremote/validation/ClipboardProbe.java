package com.pcremote.validation;

import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.PersistableBundle;

/** Test-only fixture: writes fixed samples using the actual Android clipboard API.
 * No permissions, reading, logging, transport, secrets or production dependencies. */
public final class ClipboardProbe extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String mode = intent.getStringExtra("mode");
        String text;
        if ("unicode".equals(mode)) text = "Olá, ação 👋\nhttps://example.org\n漢字";
        else if ("second".equals(mode)) text = "Outra cópia — café 🎵";
        else if ("sensitive".equals(mode)) text = "PC Remote sensitive validation sample";
        else if ("too_large".equals(mode)) text = new String(new char[65537]).replace('\0', 'a');
        else if ("baseline".equals(mode)) text = "PC Remote background baseline sample";
        else { setResultCode(1); setResultData("unknown_mode"); return; }
        ClipData clip = ClipData.newPlainText("PC Remote validation fixture", text);
        if ("sensitive".equals(mode)) {
            PersistableBundle extras = new PersistableBundle();
            extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
            clip.getDescription().setExtras(extras);
        }
        ((ClipboardManager)context.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(clip);
        setResultCode(-1);
        setResultData("written");
    }
}
