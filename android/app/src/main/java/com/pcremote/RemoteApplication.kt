package com.pcremote

import android.app.Application

class RemoteApplication : Application() {
    private val lazyClient = lazy { RemoteClient(this).also { media = MediaCoordinator(this, it) } }
    val client: RemoteClient by lazyClient
    /** The client only if something already needed it: the widget does not create it just to draw. */
    internal val existingClient: RemoteClient? get() = if (lazyClient.isInitialized()) client else null
    /** Starts the media notification and redraws the widget; created with the client. */
    internal var media: MediaCoordinator? = null
        private set
    internal val updates: UpdateManager by lazy { UpdateManager(this) }
}
