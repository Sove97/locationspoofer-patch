package com.suseoaa.locationspoofer.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.suseoaa.locationspoofer.data.model.RoutePoint
import com.suseoaa.locationspoofer.data.model.SimulatedLocation
import com.suseoaa.locationspoofer.provider.SpooferProvider
import com.suseoaa.locationspoofer.utils.TrajectorySimulator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray

class SpoofingService : Service() {

    private lateinit var locationManager: LocationManager
    private var spoofingJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO)

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_LAT = "EXTRA_LAT"
        const val EXTRA_LNG = "EXTRA_LNG"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "SpoofingServiceChannel"

        var isRunning = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val lat = intent.getDoubleExtra(EXTRA_LAT, 0.0)
                val lng = intent.getDoubleExtra(EXTRA_LNG, 0.0)
                startSpoofing(lat, lng)
            }

            ACTION_STOP -> stopSpoofing()
        }
        return START_STICKY
    }

    private fun startSpoofing(lat: Double, lng: Double) {
        if (isRunning) return

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(com.suseoaa.locationspoofer.R.string.spoofing_service_title))
            .setContentText(
                getString(
                    com.suseoaa.locationspoofer.R.string.spoofing_service_content,
                    lat.toString(),
                    lng.toString()
                )
            )
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // Ignore, continue running without foreground status
        }
        isRunning = true

        setupTestProvider(LocationManager.GPS_PROVIDER)
        setupTestProvider(LocationManager.NETWORK_PROVIDER)

        spoofingJob = serviceScope.launch {
            while (isActive) {
                val currentLoc = computeCurrentLocation()
                pushLocation(LocationManager.GPS_PROVIDER, currentLoc)
                pushLocation(LocationManager.NETWORK_PROVIDER, currentLoc)
                
                // 在路线模拟模式下使用更高的刷新率以实现平滑移动
                val updateInterval = if (SpooferProvider.isRouteMode) 100L else 500L
                delay(updateInterval)
            }
        }
    }

    private fun computeCurrentLocation(): SimulatedLocation {
        val routePoints = parseRoutePoints(SpooferProvider.routeJson)
        return if (SpooferProvider.isRouteMode && routePoints.size >= 2) {
            TrajectorySimulator.calculateRoutePosition(
                routePoints,
                SpooferProvider.startTimestamp,
                SpooferProvider.simMode,
                enableJitter = SpooferProvider.enableJitter
            )
        } else {
            TrajectorySimulator.calculateSimulatedLocation(
                SpooferProvider.latitude,
                SpooferProvider.longitude,
                SpooferProvider.startTimestamp,
                SpooferProvider.simMode,
                SpooferProvider.simBearing,
                enableJitter = SpooferProvider.enableJitter
            )
        }
    }

    private fun parseRoutePoints(json: String): List<RoutePoint> {
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                RoutePoint(obj.getDouble("lat"), obj.getDouble("lng"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun stopSpoofing() {
        spoofingJob?.cancel()
        isRunning = false

        removeTestProvider(LocationManager.GPS_PROVIDER)
        removeTestProvider(LocationManager.NETWORK_PROVIDER)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    @android.annotation.SuppressLint("WrongConstant", "MissingPermission")
    private fun setupTestProvider(provider: String) {
        try {
            @Suppress("DEPRECATION")
            locationManager.addTestProvider(
                provider, false, false, false, false,
                true, true, true, Criteria.POWER_LOW, Criteria.ACCURACY_FINE
            )
            locationManager.setTestProviderEnabled(provider, true)
        } catch (e: Exception) {
        }
    }

    private fun removeTestProvider(provider: String) {
        try {
            locationManager.removeTestProvider(provider)
        } catch (e: Exception) {
        }
    }

    private fun pushLocation(provider: String, loc: SimulatedLocation) {
        try {
            // ★ 坐标系必须与 Hook 层完全一致（本补丁的核心修复点）
            //
            // Hook 层（LocationHooker 里的 Location.getLatitude/getLongitude、
            // getLastKnownLocation、AMap/Tencent/BDLocation getter）对外返回的是
            // config["lat"/"lng"]，也就是 GCJ-02。
            //
            // 而旧代码在这里把 GCJ-02 又转换成了 WGS-84 再塞进系统 TestProvider，
            // 于是"同一个模拟点"在设备上同时存在两份坐标：
            //   - Hook 层：GCJ-02（目标 App 通过 Java getter 读到的点）
            //   - 系统层：WGS-84（TestProvider 里的原始字段，通常落在 GCJ 的西南方向 300~800m）
            // 只要目标 App 有任意一条链路绕过了被 Hook 的 getter（SDK 走 JNI 直接读
            // mLatitude/mLongitude、Parcel 透传、未注入的子进程、系统融合定位等），
            // 它就会在"模拟点"和"西南方向的另一个点"之间反复横跳。
            //
            // 修复：系统 TestProvider 直接注入与 Hook 层相同的 GCJ-02 坐标，
            // 让两条链路指向同一个物理位置。若你确实需要系统层输出 WGS-84，
            // 请把下面两行换成 CoordinateUtils.gcj02ToWgs84(loc.lat, loc.lng)，
            // 但不要与 Hook 层的 GCJ-02 混用，否则横跳会复现。
            val location = Location(provider).apply {
                latitude = loc.lat
                longitude = loc.lng
                accuracy = loc.accuracy
                altitude = loc.altitude
                speed = loc.speed
                bearing = loc.bearing
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                extras = android.os.Bundle().apply {
                    putBoolean("suseoaa_mock", true)
                }
            }
            locationManager.setTestProviderLocation(provider, location)
        } catch (e: Exception) {
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(com.suseoaa.locationspoofer.R.string.spoofing_service_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
