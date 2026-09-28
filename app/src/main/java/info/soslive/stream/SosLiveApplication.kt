package info.soslive.stream

import android.app.Application
import com.facebook.FacebookSdk
import dagger.hilt.android.HiltAndroidApp
import info.soslive.stream.core.config.AppConfig

@HiltAndroidApp
class SosLiveApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // The Facebook auto-init provider is removed in the manifest, so the SDK only starts
        // when an app id is configured. Otherwise Facebook login runs in simulated mode.
        if (AppConfig.facebookConfigured) {
            FacebookSdk.setApplicationId(AppConfig.facebookAppId)
            FacebookSdk.setClientToken(AppConfig.facebookClientToken)
            @Suppress("DEPRECATION")
            FacebookSdk.sdkInitialize(this)
        }
    }
}
