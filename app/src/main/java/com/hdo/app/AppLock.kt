package com.hdo.app

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat

/** Fingerprint / face / device PIN lock using the platform BiometricPrompt (no extra dependency). */
object AppLock {
    fun available(ctx: Context): Boolean = ctx.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    fun prompt(activity: Activity, title: String, onOk: () -> Unit, onFail: () -> Unit) {
        try {
            val builder = BiometricPrompt.Builder(activity).setTitle(title)
            if (Build.VERSION.SDK_INT >= 30) {
                builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            } else {
                @Suppress("DEPRECATION")
                builder.setDeviceCredentialAllowed(true)
            }
            builder.build().authenticate(CancellationSignal(), ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) { onOk() }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) { onFail() }
            })
        } catch (e: Exception) {
            onFail()
        }
    }
}
