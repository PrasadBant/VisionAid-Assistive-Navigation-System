package com.visionaid.app.bluetooth

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID

class BluetoothSensorManager(private val context: Context) {

    companion object {
        private const val TAG = "BTSensor"
        private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        const val DEVICE_NAME = "VisionAid_ESP32"
    }

    private var bluetoothSocket: BluetoothSocket? = null
    private var isConnected = false

    private val _sensorData = MutableStateFlow<SensorData?>(null)
    val sensorData: StateFlow<SensorData?> = _sensorData

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus

    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
            if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
                _connectionStatus.value = ConnectionStatus.UNAVAILABLE
                return@withContext false
            }
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                _connectionStatus.value = ConnectionStatus.PERMISSION_DENIED
                return@withContext false
            }
            val device: BluetoothDevice? = bluetoothAdapter.bondedDevices
                ?.find { it.name == DEVICE_NAME }

            if (device == null) {
                _connectionStatus.value = ConnectionStatus.NOT_FOUND
                return@withContext false
            }

            _connectionStatus.value = ConnectionStatus.CONNECTING
            bluetoothAdapter.cancelDiscovery()
            bluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            bluetoothSocket?.connect()
            isConnected = true
            _connectionStatus.value = ConnectionStatus.CONNECTED
            startReading()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed: ${e.message}")
            _connectionStatus.value = ConnectionStatus.DISCONNECTED
            false
        }
    }

    private suspend fun startReading() = withContext(Dispatchers.IO) {
        try {
            val reader = BufferedReader(InputStreamReader(bluetoothSocket?.inputStream))
            while (isConnected) {
                val line = reader.readLine() ?: break
                try {
                    _sensorData.value = parseSensorData(JSONObject(line))
                } catch (e: Exception) {
                    Log.w(TAG, "Parse error: $line")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Read error: ${e.message}")
            _connectionStatus.value = ConnectionStatus.DISCONNECTED
            isConnected = false
        }
    }

    private fun parseSensorData(json: JSONObject): SensorData {
        return SensorData(
            distance = json.optDouble("distance", 0.0).toFloat(),
            tilt = json.optBoolean("tilt", false),
            slopeDown = json.optBoolean("slopeDown", false),
            slopeUp = json.optBoolean("slopeUp", false),
            tiltLeft = json.optBoolean("tiltLeft", false),
            tiltRight = json.optBoolean("tiltRight", false),
            shake = json.optBoolean("shake", false),
            ax = json.optDouble("axG", json.optDouble("ax", 0.0)).toFloat(),
            ay = json.optDouble("ayG", json.optDouble("ay", 0.0)).toFloat(),
            az = json.optDouble("azG", json.optDouble("az", 0.0)).toFloat()
        )
    }

    fun disconnect() {
        isConnected = false
        try {
            bluetoothSocket?.close()
            bluetoothSocket = null
            _connectionStatus.value = ConnectionStatus.DISCONNECTED
        } catch (e: Exception) {
            Log.e(TAG, "Disconnect error: ${e.message}")
        }
    }
}
