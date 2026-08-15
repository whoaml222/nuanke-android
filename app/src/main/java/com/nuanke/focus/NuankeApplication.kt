package com.nuanke.focus

import android.app.Application
import com.nuanke.focus.data.AppStore

class NuankeApplication : Application() {
    val store: AppStore by lazy { AppStore(applicationContext) }
}

