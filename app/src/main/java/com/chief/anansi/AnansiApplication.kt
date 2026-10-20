package com.chief.anansi

import android.app.Application
import coil3.SingletonImageLoader
import com.chief.anansi.di.AppContainer

class AnansiApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        SingletonImageLoader.setSafe { container.imageLoader }
    }
}
