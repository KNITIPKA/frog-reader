package com.example.frogreader.ui.lock

import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.frogreader.R

/** Full-screen gate shown when the biometric app lock is enabled. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LockScreen(onUnlocked: () -> Unit) {
    val context = LocalContext.current
    val promptTitle = stringResource(R.string.lock_prompt_title)

    fun authenticate() {
        // A restored setting must never strand the user on a device that no
        // longer has a fingerprint, PIN or other secure credential configured.
        if (
            android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q ||
            !canUseAppLock(context)
        ) {
            onUnlocked()
            return
        }
        val builder = BiometricPrompt.Builder(context)
            .setTitle(promptTitle)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(
                Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL,
            )
        } else {
            // setAllowedAuthenticators(int) is API 30. Android 10 exposes the
            // same screen-lock fallback through this older builder method.
            @Suppress("DEPRECATION")
            builder.setDeviceCredentialAllowed(true)
        }
        val prompt = builder.build()
        prompt.authenticate(
            CancellationSignal(),
            context.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult?,
                ) {
                    onUnlocked()
                }
            },
        )
    }

    LaunchedEffect(Unit) { authenticate() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = MaterialShapes.Cookie12Sided.toShape(),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Fingerprint,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.lock_locked_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(24.dp))
            FilledTonalButton(onClick = { authenticate() }) {
                Text(stringResource(R.string.lock_unlock))
            }
        }
    }
}

@Suppress("DEPRECATION")
internal fun canUseAppLock(context: Context): Boolean {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return false
    val manager = context.getSystemService(BiometricManager::class.java)
    val keyguard = context.getSystemService(KeyguardManager::class.java)
    return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
        manager?.canAuthenticate(
            Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL,
        ) == BiometricManager.BIOMETRIC_SUCCESS
    } else {
        manager?.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS ||
            keyguard?.isDeviceSecure == true
    }
}
