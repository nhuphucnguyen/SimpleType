package dev.phucngu.simpletype.ui

import android.Manifest
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * Invisible activity that requests RECORD_AUDIO on behalf of [dev.phucngu.simpletype.ime.SimpleTypeIME].
 *
 * A service can't show a runtime-permission dialog, so the IME launches this transparent
 * activity when the user taps the mic without having granted microphone access. It requests
 * the permission and finishes immediately, returning the user to whatever they were typing in.
 */
class MicPermissionActivity : AppCompatActivity() {

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermission.launch(Manifest.permission.RECORD_AUDIO)
    }
}
