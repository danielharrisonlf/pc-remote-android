package com.pcremote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Debug builds only: hands the app a pairing invite (the QR JSON) through adb, since
 * an emulator can neither scan the PC's QR nor see its mDNS. The invite still needs
 * the code and approval on the PC, and the certificate is pinned as usual.
 *
 * adb shell am broadcast -n com.pcremote.nativeandroid/com.pcremote.DebugInviteReceiver --es invite '<QR JSON>'
 */
class DebugInviteReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val invite = intent.getStringExtra("invite")?.let(Protocol::parseInvite) ?: return
    (context.applicationContext as RemoteApplication).client.pair(invite.host, invite.port, invite.fingerprint, invite.code)
  }
}
