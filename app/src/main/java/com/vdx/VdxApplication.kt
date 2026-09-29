package com.vdx

import android.app.Application
import com.vdx.telemetry.Telemetry

class VdxApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Essential-events telemetry (opt-in, DPDP-clean). Initialises the event
        // queue, the pending batch file, and the upload worker. No network is
        // touched until the user opts in — consent is checked before any upload.
        Telemetry.init(this)
    }
}
