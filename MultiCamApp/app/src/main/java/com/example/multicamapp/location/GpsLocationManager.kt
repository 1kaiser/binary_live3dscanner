package com.example.multicamapp.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.exifinterface.media.ExifInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

class GpsLocationManager(private val context: Context) {

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    val isGpsEnabled = mutableStateOf(false)
    val currentLocation = mutableStateOf<Location?>(null)
    val statusText = mutableStateOf("GPS Off")

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            currentLocation.value = location
            val latStr = String.format(Locale.US, "%.5f", location.latitude)
            val lonStr = String.format(Locale.US, "%.5f", location.longitude)
            val accStr = String.format(Locale.US, "±%.1fm", location.accuracy)
            statusText.value = "$latStr, $lonStr ($accStr)"
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    @SuppressLint("MissingPermission")
    fun enableGps(enable: Boolean) {
        isGpsEnabled.value = enable
        if (enable) {
            try {
                val hasGps = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                val hasNetwork = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

                if (!hasGps && !hasNetwork) {
                    statusText.value = "GPS Disabled in Settings"
                    return
                }

                statusText.value = "Acquiring GPS..."

                // Try to get last known location immediately
                val lastGps = if (hasGps) locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) else null
                val lastNet = if (hasNetwork) locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) else null
                val bestLast = lastGps ?: lastNet
                if (bestLast != null) {
                    currentLocation.value = bestLast
                    val latStr = String.format(Locale.US, "%.5f", bestLast.latitude)
                    val lonStr = String.format(Locale.US, "%.5f", bestLast.longitude)
                    statusText.value = "$latStr, $lonStr"
                }

                // Request updates
                if (hasGps) {
                    locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        1000L,
                        1.0f,
                        locationListener
                    )
                }
                if (hasNetwork) {
                    locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        1000L,
                        1.0f,
                        locationListener
                    )
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Location permission missing", e)
                statusText.value = "Permission Denied"
                isGpsEnabled.value = false
            } catch (e: Exception) {
                Log.e(TAG, "Error starting location updates", e)
                statusText.value = "GPS Error"
            }
        } else {
            try {
                locationManager.removeUpdates(locationListener)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing location updates", e)
            }
            currentLocation.value = null
            statusText.value = "GPS Off"
        }
    }

    fun onDestroy() {
        try {
            locationManager.removeUpdates(locationListener)
        } catch (ignored: Exception) {}
    }

    companion object {
        private const val TAG = "GpsLocationManager"

        fun applyGpsToExif(exif: ExifInterface, location: Location) {
            try {
                val lat = location.latitude
                val lon = location.longitude
                val alt = location.altitude

                exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, formatCoord(abs(lat)))
                exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, if (lat >= 0) "N" else "S")

                exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, formatCoord(abs(lon)))
                exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, if (lon >= 0) "E" else "W")

                exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE, "${abs(alt.toInt())}/1")
                exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF, if (alt >= 0) "0" else "1")

                val date = Date(location.time)
                val sdfDate = SimpleDateFormat("yyyy:MM:dd", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                val sdfTime = SimpleDateFormat("HH:mm:ss", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                exif.setAttribute(ExifInterface.TAG_GPS_DATESTAMP, sdfDate.format(date))
                exif.setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, sdfTime.format(date))

                exif.saveAttributes()
                Log.d(TAG, "Successfully injected GPS EXIF: $lat, $lon")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to apply GPS to EXIF", e)
            }
        }

        private fun formatCoord(coord: Double): String {
            val deg = coord.toInt()
            val remMin = (coord - deg) * 60.0
            val min = remMin.toInt()
            val sec = ((remMin - min) * 60.0 * 1000.0).toInt()
            return "$deg/1,$min/1,$sec/1000"
        }
    }
}
