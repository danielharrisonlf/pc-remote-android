package com.pcremote

import android.app.Application

class RemoteApplication : Application() {
    val client: RemoteClient by lazy { RemoteClient(this) }
}
