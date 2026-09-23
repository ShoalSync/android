package org.tidefell.android

import android.app.Application

class TidefellApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Peer.init(this)
    }
}
