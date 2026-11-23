package com.adamryman.snapandprint

import android.app.Application

class SnapApp : Application() {
    val printManager: PrintManager by lazy { PrintManager(this) }
}
