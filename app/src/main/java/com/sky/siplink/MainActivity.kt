package com.sky.siplink

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sky.siplink.ui.AppRoot
import com.sky.siplink.ui.theme.SipLinkTheme
import com.sky.siplink.ui.theme.isDarkTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val settings by engine.settings.settings.collectAsStateWithLifecycle()
            val dark = isDarkTheme(settings.themeMode)

            // Keep the system bar icons readable when the in-app theme differs from the system one.
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                )
                onDispose {}
            }

            SipLinkTheme(settings.themeMode, settings.dynamicColor) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppRoot()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Each time the app comes to the front, while the user lets it ask by itself (UpdateRules.checksOnEntry).
        engine.updates.checkOnEntry()
        engine.remote.onForeground()
    }

    override fun onStop() {
        super.onStop()
        engine.settings.flush()
    }
}
