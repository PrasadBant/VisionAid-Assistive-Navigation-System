package com.visionaid.app.bluetooth

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class WiFiSensorManager {

    companion object {
        private const val TAG = "WiFiSensor"

        // Change this to the ESP32 IP shown in Arduino Serial Monitor.
        const val ESP32_IP = "10.124.206.158"

        private val SENSOR_URLS = listOf(
            "http://$ESP32_IP/sensor",
            "http://$ESP32_IP/data"
        )

        const val POLL_INTERVAL_MS = 150L
        const val TIMEOUT_MS = 450
    }

    private val _sensorData = MutableStateFlow<SensorData?>(null)
    val sensorData: StateFlow<SensorData?> = _sensorData

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus

    private var isRunning = false

    suspend fun startPolling() = withContext(Dispatchers.IO) {
        isRunning = true
        _connectionStatus.value = ConnectionStatus.CONNECTING
        Log.i(TAG, "Starting ESP32 polling at $ESP32_IP")

        while (isRunning) {
            try {
                val json = fetchSensorData()

                if (json != null) {
                    val data = parseSensorData(json)
                    _sensorData.value = data
                    _connectionStatus.value = ConnectionStatus.CONNECTED
                } else {
                    _connectionStatus.value = ConnectionStatus.DISCONNECTED
                }
            } catch (e: Exception) {
                Log.e(TAG, "Fetch error: ${e.message}")
                _connectionStatus.value = ConnectionStatus.DISCONNECTED
            }

            delay(POLL_INTERVAL_MS)
        }
    }

    private fun fetchSensorData(): String? {
        for (urlString in SENSOR_URLS) {
            val response = fetchUrl(urlString)
            if (response != null) return response
        }

        return null
    }

    private fun fetchUrl(urlString: String): String? {
        var connection: HttpURLConnection? = null

        return try {
            connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "GET"
                connect()
            }

            if (connection.responseCode == 200) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun parseSensorData(json: String): SensorData {
        val obj = JSONObject(json)

        val ax = parseAccelerationG(obj, "axG", "ax")
        val ay = parseAccelerationG(obj, "ayG", "ay")
        val az = parseAccelerationG(obj, "azG", "az")
        val emergency = obj.optBoolean("emergency", false)

        return SensorData(
            distance = obj.optDouble("distance", 999.0).toFloat(),
            tilt = obj.optBoolean("tilt", false),
            slopeDown = obj.optBoolean("slopeDown", false),
            slopeUp = obj.optBoolean("slopeUp", false),
            tiltLeft = obj.optBoolean("tiltLeft", false),
            tiltRight = obj.optBoolean("tiltRight", false),
            shake = obj.optBoolean("shake", false) || emergency,
            ax = ax,
            ay = ay,
            az = az
        )
    }

    private fun parseAccelerationG(obj: JSONObject, gKey: String, valueKey: String): Float {
        if (obj.has(gKey)) {
            return obj.optDouble(gKey, 0.0).toFloat()
        }

        return obj.optDouble(valueKey, 0.0).toFloat()
    }

    fun stop() {
        isRunning = false
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
        Log.i(TAG, "WiFi polling stopped")
    }
}
