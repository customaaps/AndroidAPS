package app.aaps.pump.apex.interfaces

import android.bluetooth.BluetoothGatt
import app.aaps.pump.apex.ApexDriverLogic
import app.aaps.pump.apex.connectivity.commands.pump.PumpCommand

interface ApexBluetoothCallback {
    fun onConnect()
    fun onDisconnect()
    fun onPumpCommand(command: PumpCommand)

    /**
     * One notification from the pump's password characteristic (FFC2).
     *
     * Separate from [onPumpCommand] because it is not a pump frame: no header, no length, no CRC -
     * a single byte whose meaning is positional. Feeding it to the frame parser would corrupt a
     * partially assembled command.
     */
    fun onPasswordReply(value: ByteArray)

    /**
     * Run the connection-password exchange for this GATT client, on a worker thread, before the data
     * channel (FFE4) is subscribed. Called from `onMtuChanged` only when the toggle is on and the pump
     * has the password service. The pump refuses the data channel until it has seen a valid FFC1 write,
     * so this has to happen inside the handshake, not after it.
     *
     * [btConnectionId] is `ApexBluetooth.connectionId` for this attempt; the worker uses it with the
     * `gatt` to tell its own client from a newer one before every Bluetooth call.
     */
    fun onPasswordGate(gatt: BluetoothGatt, btConnectionId: Int)

    /** The data channel was refused and the password was not accepted: lock out and warn (card B), then drop the link. */
    fun onPasswordChannelRefused()

    /** The data channel was refused but the password WAS accepted: just drop the link. No lockout, no card. */
    fun onPasswordCccFailed()

    /** The last decoded FFC2 verdict, so the gate can pick the refuse outcome when FFE4 fails. Null if none yet. */
    fun currentPasswordReply(): ApexDriverLogic.PasswordReply?
}
