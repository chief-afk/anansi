package com.chief.anansi

import android.app.Application
import com.chief.anansi.di.AppContainer

class AnansiApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
