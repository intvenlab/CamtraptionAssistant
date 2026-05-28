package com.ivlabs.batterymonitor

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.coroutines.resume

enum class GattState { IDLE, CONNECTING, READY, ERROR }

class BleGattManager(private val context: Context) {

    var state by mutableStateOf(GattState.IDLE)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set
    var negotiatedMtu by mutableStateOf(23)
        private set

    private var gatt: BluetoothGatt? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // Pending coroutine continuations for sequential read/write/notify
    private var pendingReadCont: CancellableContinuation<ByteArray?>? = null
    private var pendingReadUuid: UUID? = null
    private var pendingWriteCont: CancellableContinuation<Boolean>? = null
    private var pendingWriteUuid: UUID? = null
    private var pendingNotifyCont: CancellableContinuation<ByteArray?>? = null
    private var pendingNotifyUuid: UUID? = null

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when {
                status == BluetoothGatt.GATT_SUCCESS &&
                        newState == BluetoothProfile.STATE_CONNECTED -> {
                    this@BleGattManager.gatt = gatt
                    // Request a larger MTU so the 22-byte CameraConfig write fits
                    // (default payload cap is MTU-3 = 20 bytes).
                    // onMtuChanged calls discoverServices() once negotiation completes.
                    // Fall through to discoverServices directly if the call fails immediately.
                    if (!gatt.requestMtu(256)) {
                        Log.w("BleGatt", "requestMtu(256) returned false — discovering services immediately")
                        gatt.discoverServices()
                    }
                }
                newState == BluetoothProfile.STATE_DISCONNECTED -> {
                    gatt.close()
                    this@BleGattManager.gatt = null
                    cancelPending()
                    mainHandler.post {
                        state = if (status == BluetoothGatt.GATT_SUCCESS) GattState.IDLE
                                else {
                                    errorMessage = "Disconnected (code $status)"
                                    GattState.ERROR
                                }
                    }
                }
                else -> {
                    gatt.close()
                    this@BleGattManager.gatt = null
                    cancelPending()
                    mainHandler.post {
                        errorMessage = "Connection failed (code $status)"
                        state = GattState.ERROR
                    }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d("BleGatt", "MTU negotiated: $mtu (status=$status)")
            mainHandler.post { negotiatedMtu = mtu }
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            mainHandler.post {
                state = if (status == BluetoothGatt.GATT_SUCCESS) GattState.READY
                        else {
                            errorMessage = "Service discovery failed"
                            GattState.ERROR
                        }
            }
        }

        // API 33+ path
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                deliverRead(characteristic.uuid, value, status)
            }
        }

        // Pre-API 33 path
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                deliverRead(characteristic.uuid, characteristic.value ?: byteArrayOf(), status)
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (characteristic.uuid == pendingWriteUuid) {
                val cont = pendingWriteCont
                pendingWriteCont = null
                pendingWriteUuid = null
                cont?.resume(status == BluetoothGatt.GATT_SUCCESS)
            }
        }

        // API 33+ path
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                deliverNotification(characteristic.uuid, value)
            }
        }

        // Pre-API 33 path
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                deliverNotification(characteristic.uuid, characteristic.value ?: byteArrayOf())
            }
        }
    }

    private fun deliverRead(uuid: UUID, value: ByteArray, status: Int) {
        if (uuid == pendingReadUuid) {
            val cont = pendingReadCont
            pendingReadCont = null
            pendingReadUuid = null
            cont?.resume(if (status == BluetoothGatt.GATT_SUCCESS) value else null)
        }
    }

    private fun deliverNotification(uuid: UUID, value: ByteArray) {
        if (uuid == pendingNotifyUuid) {
            val cont = pendingNotifyCont
            pendingNotifyCont = null
            pendingNotifyUuid = null
            cont?.resume(value)
        }
    }

    private fun cancelPending() {
        pendingReadCont?.cancel()
        pendingReadCont = null
        pendingReadUuid = null
        pendingWriteCont?.cancel()
        pendingWriteCont = null
        pendingWriteUuid = null
        pendingNotifyCont?.cancel()
        pendingNotifyCont = null
        pendingNotifyUuid = null
    }

    // ---------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------

    fun connect(device: BluetoothDevice) {
        gatt?.close()
        errorMessage = null
        negotiatedMtu = 23  // reset to default until onMtuChanged fires
        state = GattState.CONNECTING
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    suspend fun readCharacteristic(uuid: UUID): ByteArray? {
        val characteristic = gatt?.getService(GattUuids.SERVICE)?.getCharacteristic(uuid)
            ?: return null
        return suspendCancellableCoroutine { cont ->
            pendingReadCont = cont
            pendingReadUuid = uuid
            cont.invokeOnCancellation { pendingReadCont = null; pendingReadUuid = null }
            @Suppress("DEPRECATION")
            gatt?.readCharacteristic(characteristic)
        }
    }

    suspend fun writeCharacteristic(uuid: UUID, value: ByteArray): Boolean {
        val characteristic = gatt?.getService(GattUuids.SERVICE)?.getCharacteristic(uuid)
            ?: return false
        return suspendCancellableCoroutine { cont ->
            pendingWriteCont = cont
            pendingWriteUuid = uuid
            cont.invokeOnCancellation { pendingWriteCont = null; pendingWriteUuid = null }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt?.writeCharacteristic(
                    characteristic,
                    value,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                )
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = value
                @Suppress("DEPRECATION")
                gatt?.writeCharacteristic(characteristic)
            }
        }
    }

    fun enableNotification(uuid: UUID): Boolean {
        val characteristic = gatt?.getService(GattUuids.SERVICE)?.getCharacteristic(uuid)
            ?: return false
        gatt?.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        ) ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt?.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt?.writeDescriptor(descriptor)
        }
        return true
    }

    suspend fun waitForNotification(uuid: UUID, timeoutMs: Long = 3000): ByteArray? {
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                pendingNotifyCont = cont
                pendingNotifyUuid = uuid
                cont.invokeOnCancellation { pendingNotifyCont = null; pendingNotifyUuid = null }
            }
        }
    }

    fun disconnect() {
        gatt?.disconnect()
    }

    fun close() {
        cancelPending()
        gatt?.close()
        gatt = null
        state = GattState.IDLE
    }
}
