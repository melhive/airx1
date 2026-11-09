package com.melhive.airx1.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.melhive.airx1.service.HandTrackingService

/** Invisible trampoline used by the Quick Settings tile to start the camera from the foreground. */
class StartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Permissions.camera(this) && Permissions.accessibilityEnabled(this)) {
            HandTrackingService.start(this)
        } else {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        finish()
    }
}
