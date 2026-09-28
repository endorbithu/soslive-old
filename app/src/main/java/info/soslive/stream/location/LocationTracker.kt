package info.soslive.stream.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority
import dagger.hilt.android.qualifiers.ApplicationContext
import info.soslive.stream.core.config.AppConfig
import info.soslive.stream.domain.model.GeoPoint
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** Wraps the fused location provider. All calls are no-ops without location permission. */
@Singleton
class LocationTracker @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val client = LocationServices.getFusedLocationProviderClient(context)

    private val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, AppConfig.LOCATION_UPDATE_INTERVAL_MILLIS)
        .setMinUpdateIntervalMillis(5_000)
        .build()

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** A fresh fix if one arrives within [timeoutMillis], otherwise the last known location. */
    @SuppressLint("MissingPermission")
    suspend fun currentLocation(timeoutMillis: Long = 4_000): GeoPoint? {
        if (!hasPermission()) return null
        val fresh = withTimeoutOrNull(timeoutMillis) {
            runCatching { client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await() }.getOrNull()
        }
        val location = fresh ?: runCatching { client.lastLocation.await() }.getOrNull()
        return location?.toGeoPoint()
    }

    @SuppressLint("MissingPermission")
    fun updates(): Flow<GeoPoint> {
        if (!hasPermission()) return emptyFlow()
        return callbackFlow {
            val callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    result.lastLocation?.let { trySend(it.toGeoPoint()) }
                }
            }
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
            awaitClose { client.removeLocationUpdates(callback) }
        }
    }

    /**
     * Returns an exception the UI can resolve (shows the "turn on location" system dialog) when
     * location services are off, null when everything is fine or cannot be fixed.
     */
    suspend fun settingsResolution(): ResolvableApiException? = try {
        LocationServices.getSettingsClient(context)
            .checkLocationSettings(LocationSettingsRequest.Builder().addLocationRequest(request).setAlwaysShow(true).build())
            .await()
        null
    } catch (e: ResolvableApiException) {
        e
    } catch (_: Exception) {
        null
    }

    private fun Location.toGeoPoint() = GeoPoint(latitude, longitude, if (hasAccuracy()) accuracy else null)
}
