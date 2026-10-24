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
 * adb shell am broadcast -a com.pcremote.DEBUG_PAIR_WITH_CODE -n com.pcremote.nativeandroid/com.pcremote.DebugInviteReceiver --es pcHost 10.0.2.2 --ei pcPort 43127 --es pcName 'PC A' --es pcId '<UUID>' --es pairCode 12345678
 */
class DebugInviteReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action == ACTION_PAIR_WITH_CODE) {
      val host=intent.getStringExtra("pcHost") ?: return
      val port=intent.getIntExtra("pcPort",-1)
      val name=intent.getStringExtra("pcName").orEmpty().ifBlank { "PC no endereço" }
      val id=intent.getStringExtra("pcId").orEmpty()
      val code=intent.getStringExtra("pairCode") ?: return
      (context.applicationContext as RemoteApplication).client.pairWithCode(DiscoveredComputer(name,host,port,id),code)
      return
    }
    val invite = intent.getStringExtra("invite")?.let(Protocol::parseInvite) ?: return
    (context.applicationContext as RemoteApplication).client.pair(invite.host, invite.port, invite.fingerprint, invite.code)
  }

  companion object {
    const val ACTION_PAIR_WITH_CODE = "com.pcremote.DEBUG_PAIR_WITH_CODE"
  }
}
