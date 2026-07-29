package com.vdx

import android.app.Application
import io.sentry.Sentry
import io.sentry.android.core.SentryAndroid

class VdxApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SentryAndroid.init(this) { options ->
            options.dsn = "https://examplePublicKey@o0.ingest.sentry.io/0"
            options.tracesSampleRate = 0.2
            options.isEnableAppHangTracking = true
            options.isAnrReportAfterMs = 5000L
        }
    }
}
