package com.luminapaint.draw

import com.google.android.gms.ads.MobileAds
import org.fossify.commons.FossifyApp
import org.fossify.commons.helpers.ensureBackgroundThread

class App : FossifyApp() {
    override fun onCreate() {
        super.onCreate()
        ensureBackgroundThread {
            runCatching {
                MobileAds.initialize(this) {}
            }
        }
    }
}
