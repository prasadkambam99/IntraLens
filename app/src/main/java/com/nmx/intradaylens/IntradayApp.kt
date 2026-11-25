package com.nmx.intradaylens

import android.app.Application
import com.nmx.intradaylens.work.MarketWorker

class IntradayApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MarketWorker.schedule(this)
    }
}
