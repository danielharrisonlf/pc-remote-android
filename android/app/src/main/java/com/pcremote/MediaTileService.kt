package com.pcremote

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * "Mídia do PC" in Quick Settings: plays or pauses the PC. Connected, the command
 * goes straight away; otherwise MediaControlService connects first, or, when Android
 * does not let it start from here, the client connects and sends it by itself.
 */
internal class MediaTileService : TileService() {
  private var unsubscribe: (() -> Unit)? = null

  override fun onStartListening() {
    super.onStartListening()
    unsubscribe?.invoke()
    unsubscribe = (application as RemoteApplication).client.observe { render(it) }
  }

  override fun onStopListening() {
    unsubscribe?.invoke()
    unsubscribe = null
    super.onStopListening()
  }

  override fun onClick() {
    super.onClick()
    val client = (application as RemoteApplication).client
    val state = client.view
    if (state.computers.isEmpty()) return
    if (state.status == "Conectado" && state.snapshot != null) { MediaCommands.direct(client, MediaRules.TOGGLE); return }
    if (!MediaControlService.command(this, MediaRules.TOGGLE)) MediaCommands.direct(client, MediaRules.TOGGLE)
  }

  private fun render(state: UiState) {
    val tile = qsTile ?: return
    val content = MediaRules.widget(state)
    tile.label = "Mídia do PC"
    tile.state = when {
      state.computers.isEmpty() -> Tile.STATE_UNAVAILABLE
      content.playing -> Tile.STATE_ACTIVE
      else -> Tile.STATE_INACTIVE
    }
    tile.contentDescription = if (content.playing) "Pausar no PC" else "Tocar no PC"
    if (Build.VERSION.SDK_INT >= 29) tile.subtitle = if (state.status == "Conectado" && state.snapshot?.sessions?.isNotEmpty() == true) content.title else "Tocar ou pausar"
    tile.updateTile()
  }
}
