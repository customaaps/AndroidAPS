/*
 * Libre 2 GATT/authentication sequence adapted from JugglucoNG bb5757761a,
 * Libre2GattCallback.java. Copyright (C) 2021 Jaap Korthals Altes.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
@file:Suppress("DEPRECATION")
package app.aaps.plugins.source.libre2

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.os.PowerManager
import android.util.Log
import java.io.IOException
import java.util.UUID

/** All state transitions and native calls run on the decoder service's handler. */
@SuppressLint("MissingPermission")
internal class Libre2Bluetooth(
    private val context: Context,
    private val handler: Handler,
    private val pairing: Libre2Pairing,
    private val reserveCounter: () -> Int,
    private val onConnected: () -> Unit,
    private val onPacket: (ByteArray) -> Unit,
    private val onFailure: (Exception) -> Unit,
) {
    private enum class Stage { CONNECTING, DISCOVERING, LOGIN_NOTIFY, CHALLENGE, PROOF, UNLOCK, RAW_NOTIFY, STREAMING }
    private var stage = Stage.CONNECTING
    private var gatt: BluetoothGatt? = null
    private var stopped = false
    private var session = 0
    private var proofPrefix: ByteArray? = null
    private val assembler = Libre2PacketAssembler()
    private val operations = Libre2GattQueue()
    private val deadline = Runnable { fail(IOException("Libre2 Bluetooth session timed out")) }

    private fun permitted() {
        if (Build.VERSION.SDK_INT >= 31 && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            throw SecurityException("Bluetooth connect permission is required")
    }
    private fun arm(ms: Long = 30_000) {
        handler.removeCallbacks(deadline)
        handler.postDelayed(deadline, ms)
    }
    private fun dispatch(connection: BluetoothGatt, block: () -> Unit) {
        // Hold only while queued callbacks and packet processing execute, never between readings.
        val wakeLock = try {
            context.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AAPS:Libre2Packet").apply {
                    setReferenceCounted(false)
                    acquire(30_000)
                }
        } catch (_: Exception) {
            // A platform power-management error must not crash a Bluetooth callback thread.
            Log.w("Libre2", "Could not acquire packet wake lock")
            null
        }
        val accepted = handler.post {
            try {
                if (stopped || connection !== gatt) return@post
                try { permitted(); block() } catch (error: Exception) { fail(error) }
            } finally { if (wakeLock?.isHeld == true) wakeLock.release() }
        }
        if (!accepted && wakeLock?.isHeld == true) wakeLock.release()
    }

    fun connect() {
        check(gatt == null && !stopped)
        permitted()
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw IOException("Bluetooth is unavailable")
        if (!adapter.isEnabled) throw IOException("Bluetooth is switched off")
        arm()
        gatt = adapter.getRemoteDevice(pairing.address)
            .connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: throw IOException("Cannot open Libre2 GATT connection")
    }

    private fun characteristic(uuid: UUID): BluetoothGattCharacteristic =
        gatt?.getService(SERVICE)?.getCharacteristic(uuid) ?: throw IOException("Libre2 GATT characteristic is missing")

    private fun write(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        operations.enqueue("write" to characteristic.uuid) { writeNow(characteristic, value) }
    }

    private fun writeNow(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        permitted()
        val connection = gatt ?: throw IOException("Libre2 is disconnected")
        val writeType = when {
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0 -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            else -> throw IOException("Libre2 authentication characteristic is not writable")
        }
        val accepted = if (Build.VERSION.SDK_INT >= 33)
            connection.writeCharacteristic(characteristic, value, writeType) == BluetoothStatusCodes.SUCCESS
        else {
            characteristic.writeType = writeType
            characteristic.value = value
            connection.writeCharacteristic(characteristic)
        }
        if (!accepted) throw IOException("Libre2 GATT write was rejected")
        arm()
    }

    private fun subscribe(uuid: UUID) {
        operations.enqueue("descriptor" to uuid) { subscribeNow(uuid) }
    }

    private fun subscribeNow(uuid: UUID) {
        permitted()
        val connection = gatt ?: throw IOException("Libre2 is disconnected")
        val target = characteristic(uuid)
        if (!connection.setCharacteristicNotification(target, true)) throw IOException("Cannot enable Libre2 notifications")
        val descriptor = target.getDescriptor(CCCD) ?: throw IOException("Libre2 notification descriptor is missing")
        val accepted = if (Build.VERSION.SDK_INT >= 33)
            connection.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
        else {
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            connection.writeDescriptor(descriptor)
        }
        if (!accepted) throw IOException("Libre2 notification write was rejected")
        arm()
    }

    private fun authenticate(challenge: ByteArray) {
        require(challenge.size == 14) { "Invalid Libre2 security challenge" }
        require(pairing.auth.size == 10 || pairing.auth.size == 12)
        val nonce = if (pairing.auth.size == 12)
            (pairing.auth[10].toInt() and 255) or ((pairing.auth[11].toInt() and 255) shl 8) else -1
        session = Libre2NativeDecoder.v1(28960, nonce, pairing.uid, null)
        if (session <= 0) throw IOException("Libre2 BLE authentication failed")
        val payload = Libre2NativeDecoder.v2(6505, session, byteArrayOf(1, 31), pairing.auth.copyOf(10) + challenge)
            ?: throw IOException("Libre2 BLE authentication returned no payload")
        require(payload.size == 19) { "Invalid Libre2 security response" }
        proofPrefix = null
        stage = Stage.PROOF
        write(characteristic(LOGIN), payload)
    }

    private fun notify(uuid: UUID, value: ByteArray, receivedAt: Long) {
        if (uuid == LOGIN && pairing.generation2) {
            when (stage) {
                Stage.CHALLENGE -> authenticate(value)
                Stage.PROOF -> {
                    if (value.size == 7) {
                        require(proofPrefix == null) { "Duplicate Libre2 security fragment" }
                        proofPrefix = value
                    } else {
                        require(value.size == 18 && proofPrefix != null) { "Invalid Libre2 security fragment sequence" }
                        val proof = proofPrefix!! + value
                        proofPrefix = null
                        if (Libre2NativeDecoder.v1(29465, session, byteArrayOf(1), proof) != 0)
                            throw IOException("Libre2 rejected BLE authentication")
                        stage = Stage.RAW_NOTIFY
                        subscribe(RAW)
                    }
                }
                else -> Unit
            }
        } else if (uuid == RAW && stage == Stage.STREAMING) {
            val packet = assembler.accept(value, receivedAt) ?: return
            Log.d("Libre2", "Complete BLE packet received at elapsedMs=$receivedAt")
            val decoded = if (pairing.generation2)
                Libre2NativeDecoder.v2(773, session, packet, null) ?: throw IOException("Libre2 BLE decryption failed")
            else packet
            arm(180_000)
            onPacket(decoded)
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(connection: BluetoothGatt, status: Int, newState: Int) = dispatch(connection) {
            if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED)
                throw IOException("Libre2 Bluetooth disconnected (status=$status)")
            if (newState == BluetoothProfile.STATE_CONNECTED && stage == Stage.CONNECTING) {
                stage = Stage.DISCOVERING
                if (!connection.discoverServices()) throw IOException("Cannot discover Libre2 services")
                arm()
            }
        }
        override fun onServicesDiscovered(connection: BluetoothGatt, status: Int) = dispatch(connection) {
            if (stage != Stage.DISCOVERING) return@dispatch
            if (status != BluetoothGatt.GATT_SUCCESS) throw IOException("Libre2 service discovery failed")
            characteristic(LOGIN)
            characteristic(RAW)
            if (pairing.generation2) {
                stage = Stage.LOGIN_NOTIFY
                subscribe(LOGIN)
            } else {
                val counter = reserveCounter()
                val key = Libre2NativeDecoder.unlockPayload(pairing.uid, pairing.patchInfo, 0, pairing.enabledAtSeconds, counter)
                stage = Stage.UNLOCK
                write(characteristic(LOGIN), key)
            }
        }
        override fun onDescriptorWrite(connection: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) = dispatch(connection) {
            if (status != BluetoothGatt.GATT_SUCCESS) throw IOException("Libre2 notification setup failed")
            if (!operations.complete("descriptor" to descriptor.characteristic.uuid)) return@dispatch
            when {
                descriptor.characteristic.uuid == LOGIN && stage == Stage.LOGIN_NOTIFY -> {
                    stage = Stage.CHALLENGE
                    write(characteristic(LOGIN), byteArrayOf(32))
                }
                descriptor.characteristic.uuid == RAW && stage == Stage.RAW_NOTIFY -> {
                    stage = Stage.STREAMING
                    // Parameter update applies to this sensor link, never to a pump's GATT connection.
                    connection.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
                    arm(180_000)
                    onConnected()
                }
            }
        }
        override fun onCharacteristicWrite(connection: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) = dispatch(connection) {
            if (status != BluetoothGatt.GATT_SUCCESS) throw IOException("Libre2 GATT authentication write failed")
            if (!operations.complete("write" to characteristic.uuid)) return@dispatch
            if (characteristic.uuid == LOGIN && stage == Stage.UNLOCK) {
                stage = Stage.RAW_NOTIFY
                subscribe(RAW)
            }
        }
        override fun onCharacteristicChanged(connection: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val uuid = characteristic.uuid
            val value = characteristic.value?.copyOf() ?: return
            val receivedAt = SystemClock.elapsedRealtime()
            dispatch(connection) { notify(uuid, value, receivedAt) }
        }
        override fun onCharacteristicChanged(connection: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            val uuid = characteristic.uuid
            val copy = value.copyOf()
            val receivedAt = SystemClock.elapsedRealtime()
            dispatch(connection) { notify(uuid, copy, receivedAt) }
        }
    }

    private fun fail(error: Exception) {
        if (stopped) return
        close()
        onFailure(error)
    }

    fun close() {
        if (stopped) return
        stopped = true
        operations.close()
        handler.removeCallbacks(deadline)
        assembler.reset()
        proofPrefix = null
        if (session > 0) runCatching { Libre2NativeDecoder.v1(37400, session, null, null) }
        session = 0
        val connection = gatt
        gatt = null
        if (connection != null) {
            runCatching { connection.disconnect() }
            runCatching { connection.close() }
        }
    }

    companion object {
        private val SERVICE = UUID.fromString("0000fde3-0000-1000-8000-00805f9b34fb")
        private val LOGIN = UUID.fromString("0000f001-0000-1000-8000-00805f9b34fb")
        private val RAW = UUID.fromString("0000f002-0000-1000-8000-00805f9b34fb")
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
