package com.netbrake.app

import android.app.Application
import com.netbrake.app.data.RuleRepo

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        RuleRepo.init(this)
    }
}
