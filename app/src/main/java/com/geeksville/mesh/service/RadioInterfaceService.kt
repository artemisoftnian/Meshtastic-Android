package com.geeksville.mesh.service

import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.RemoteException
import androidx.core.content.edit
import com.geeksville.android.BinaryLogFile
import com.geeksville.android.GeeksvilleApplication
import com.geeksville.android.Logging
import com.geeksville.concurrent.DeferredExecution
import com.geeksville.concurrent.handledLaunch
import com.geeksville.mesh.IRadioInterfaceService
<<<<<<< HEAD
=======
import com.geeksville.util.anonymize
>>>>>>> upstream/master
import com.geeksville.util.exceptionReporter
import com.geeksville.util.toRemoteExceptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import java.lang.reflect.Method
import java.util.*


/* Info for the esp32 device side code.  See that source for the 'gold' standard docs on this interface.

MeshBluetoothService UUID 6ba1b218-15a8-461f-9fa8-5dcae273eafd

FIXME - notify vs indication for fromradio output.  Using notify for now, not sure if that is best
FIXME - in the esp32 mesh management code, occasionally mirror the current net db to flash, so that if we reboot we still have a good guess of users who are out there.
FIXME - make sure this protocol is guaranteed robust and won't drop packets

"According to the BLE specification the notification length can be max ATT_MTU - 3. The 3 bytes subtracted is the 3-byte header(OP-code (operation, 1 byte) and the attribute handle (2 bytes)).
In BLE 4.1 the ATT_MTU is 23 bytes (20 bytes for payload), but in BLE 4.2 the ATT_MTU can be negotiated up to 247 bytes."

MAXPACKET is 256? look into what the lora lib uses. FIXME

Characteristics:
UUID
properties
description

8ba2bcc2-ee02-4a55-a531-c525c5e454d5
read
fromradio - contains a newly received packet destined towards the phone (up to MAXPACKET bytes? per packet).
After reading the esp32 will put the next packet in this mailbox.  If the FIFO is empty it will put an empty packet in this
mailbox.

f75c76d2-129e-4dad-a1dd-7866124401e7
write
toradio - write ToRadio protobufs to this charstic to send them (up to MAXPACKET len)

ed9da18c-a800-4f66-a670-aa7547e34453
read|notify|write
fromnum - the current packet # in the message waiting inside fromradio, if the phone sees this notify it should read messages
until it catches up with this number.
  The phone can write to this register to go backwards up to FIXME packets, to handle the rare case of a fromradio packet was dropped after the esp32
callback was called, but before it arrives at the phone.  If the phone writes to this register the esp32 will discard older packets and put the next packet >= fromnum in fromradio.
When the esp32 advances fromnum, it will delay doing the notify by 100ms, in the hopes that the notify will never actally need to be sent if the phone is already pulling from fromradio.
  Note: that if the phone ever sees this number decrease, it means the esp32 has rebooted.

meshMyNodeCharacteristic("ea9f3f82-8dc4-4733-9452-1f6da28892a2", BLECharacteristic::PROPERTY_READ)
mynode - read/write this to access a MyNodeInfo protobuf

meshNodeInfoCharacteristic("d31e02e0-c8ab-4d3f-9cc9-0b8466bdabe8", BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_READ),
nodeinfo - read this to get a series of node infos (ending with a null empty record), write to this to restart the read statemachine that returns all the node infos

meshRadioCharacteristic("b56786c8-839a-44a1-b98e-a1724c4a0262", BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_READ),
radio - read/write this to access a RadioConfig protobuf

meshOwnerCharacteristic("6ff1d8b6-e2de-41e3-8c0b-8fa384f64eb6", BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_READ)
owner - read/write this to access a User protobuf

Re: queue management
Not all messages are kept in the fromradio queue (filtered based on SubPacket):
* only the most recent Position and User messages for a particular node are kept
* all Data SubPackets are kept
* No WantNodeNum / DenyNodeNum messages are kept
A variable keepAllPackets, if set to true will suppress this behavior and instead keep everything for forwarding to the phone (for debugging)

 */

/**
 * Handles the bluetooth link with a mesh radio device.  Does not cache any device state,
 * just does bluetooth comms etc...
 *
 * This service is not exposed outside of this process.
 *
 * Note - this class intentionally dumb.  It doesn't understand protobuf framing etc...
 * It is designed to be simple so it can be stubbed out with a simulated version as needed.
 */
class RadioInterfaceService : Service(), Logging {

    companion object : Logging {
        /**
         * The RECEIVED_FROMRADIO
         * Payload will be the raw bytes which were contained within a MeshProtos.FromRadio protobuf
         */
        const val RECEIVE_FROMRADIO_ACTION = "$prefix.RECEIVE_FROMRADIO"

        /**
         * This is broadcast when connection state changed
         */
        const val RADIO_CONNECTED_ACTION = "$prefix.CONNECT_CHANGED"

        /// this service UUID is publically visible for scanning
        val BTM_SERVICE_UUID = UUID.fromString("6ba1b218-15a8-461f-9fa8-5dcae273eafd")

        private val BTM_FROMRADIO_CHARACTER =
            UUID.fromString("8ba2bcc2-ee02-4a55-a531-c525c5e454d5")
        private val BTM_TORADIO_CHARACTER =
            UUID.fromString("f75c76d2-129e-4dad-a1dd-7866124401e7")
        private val BTM_FROMNUM_CHARACTER =
            UUID.fromString("ed9da18c-a800-4f66-a670-aa7547e34453")

        /// mynode - read/write this to access a MyNodeInfo protobuf
        private val BTM_MYNODE_CHARACTER =
            UUID.fromString("ea9f3f82-8dc4-4733-9452-1f6da28892a2")

        /// nodeinfo - read this to get a series of node infos (ending with a null empty record), write to this to restart the read statemachine that returns all the node infos
        private val BTM_NODEINFO_CHARACTER =
            UUID.fromString("d31e02e0-c8ab-4d3f-9cc9-0b8466bdabe8")

        /// radio - read/write this to access a RadioConfig protobuf
        private val BTM_RADIO_CHARACTER =
            UUID.fromString("b56786c8-839a-44a1-b98e-a1724c4a0262")

        /// owner - read/write this to access a User protobuf
        private val BTM_OWNER_CHARACTER =
            UUID.fromString("6ff1d8b6-e2de-41e3-8c0b-8fa384f64eb6")

        private const val DEVADDR_KEY = "devAddr"

        /// If our service is currently running, this pointer can be used to reach it (in case setBondedDeviceAddress is called)
        private var runningService: RadioInterfaceService? = null

<<<<<<< HEAD
=======
        /**
         * Temp hack (until old API deprecated), try using just the new API now
         */
        var isOldApi: Boolean? = false

>>>>>>> upstream/master
        /// This is public only so that SimRadio can bootstrap our message flow
        fun broadcastReceivedFromRadio(context: Context, payload: ByteArray) {
            val intent = Intent(RECEIVE_FROMRADIO_ACTION)
            intent.putExtra(EXTRA_PAYLOAD, payload)
            context.sendBroadcast(intent)
        }

        private fun getPrefs(context: Context) =
            context.getSharedPreferences("radio-prefs", Context.MODE_PRIVATE)

        /// Get our bluetooth adapter (should always succeed except on emulator
        private fun getBluetoothAdapter(context: Context): BluetoothAdapter? {
            val bluetoothManager =
                context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            return bluetoothManager.adapter
        }

        /// Return the device we are configured to use, or null for none
        fun getBondedDeviceAddress(context: Context): String? {

            val allPaired =
                getBluetoothAdapter(context)?.bondedDevices.orEmpty().map { it.address }.toSet()

            // If the user has unpaired our device, treat things as if we don't have one
            val addr = getPrefs(context).getString(DEVADDR_KEY, null)
            return if (addr != null && !allPaired.contains(addr)) {
                warn("Ignoring stale bond to $addr")
                null
            } else
                addr
        }

<<<<<<< HEAD
        fun setBondedDeviceAddress(context: Context, addr: String?) {
            getPrefs(context).edit(commit = true) {
                if (addr == null)
                    this.remove(DEVADDR_KEY)
                else
                    putString(DEVADDR_KEY, addr)
            }

            // Record that this use has configured a radio
            GeeksvilleApplication.analytics.track(
                "mesh_bond"
            )

            // Force the service to reconnect
            val s = runningService
            if (s != null) {
                // Ignore any errors that happen while closing old device
                exceptionReporter {
                    info("shutting down old service")
                    s.setEnabled(false) // nasty, needed to force the next setEnabled call to reconnect
                }
                info("Setting enable on the running radio service")
                s.setEnabled(addr != null)
            }
            if (addr != null) {
                info("We have a device addr now, starting mesh service")
                MeshService.startService(context)
            }
        }
    }
=======
                if (address != null && !allPaired.contains(address)) {
                    warn("Ignoring stale bond to ${address.anonymize}")
                    null
                } else
                    address
            }


        /// Can we use the modern BLE scan API?
        fun hasCompanionDeviceApi(context: Context): Boolean = false /* ALAS - not ready for production yet
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val res =
                    context.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)
                debug("CompanionDevice API available=$res")
                res
            } else {
                warn("CompanionDevice API not available, falling back to classic scan")
                false
            } */
>>>>>>> upstream/master

        /**
         * this is created in onCreate()
         * We do an ugly hack of keeping it in the singleton so we can share it for the rare software update case
         */
        var safe: SafeBluetooth? = null
    }


    /// Our BLE device
    val device get() = safe?.gatt ?: throw RadioNotConnectedException("No GATT")

    /// Our service
    val service get() = device.getService(BTM_SERVICE_UUID)
    //.services.find { it.uuid == BTM_SERVICE_UUID }!!

    private lateinit var fromNum: BluetoothGattCharacteristic

    private val logSends = false
    private val logReceives = false
    lateinit var sentPacketsLog: BinaryLogFile // inited in onCreate
    lateinit var receivedPacketsLog: BinaryLogFile

    private var isConnected = false

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    /// Work that users of our service want done, which might get deferred until after
    /// we have completed our initial connection
    private val clientOperations = DeferredExecution()

    private fun broadcastConnectionChanged(isConnected: Boolean) {
        debug("Broadcasting connection=$isConnected")
        val intent = Intent(RADIO_CONNECTED_ACTION)
        intent.putExtra(EXTRA_CONNECTED, isConnected)
        sendBroadcast(intent)
    }

    /// Send a packet/command out the radio link
    private fun handleSendToRadio(p: ByteArray) {
<<<<<<< HEAD

        // For debugging/logging purposes ONLY we convert back into a protobuf for readability
        // al proto = MeshProtos.ToRadio.parseFrom(p)

        debug("sending to radio")
        doAsyncWrite(BTM_TORADIO_CHARACTER, p)
        if (logSends) {
            sentPacketsLog.write(p)
            sentPacketsLog.flush()
=======
        // Do this in the IO thread because it might take a while (and we don't care about the result code)
        serviceScope.handledLaunch {
            try {
                debug("sending to radio")
                doWrite(
                    BTM_TORADIO_CHARACTER,
                    p
                ) // Do a synchronous write, so that we can then do our reads if needed
                if (logSends) {
                    sentPacketsLog.write(p)
                    sentPacketsLog.flush()
                }

                if (isFirstSend) {
                    isFirstSend = false
                    doReadFromRadio(false)
                }
            } catch (ex: Exception) {
                errormsg("Ignoring sendToRadio exception: $ex")
            }
>>>>>>> upstream/master
        }
    }

    // Handle an incoming packet from the radio, broadcasts it as an android intent
    private fun handleFromRadio(p: ByteArray) {
        if (logReceives) {
            receivedPacketsLog.write(p)
            receivedPacketsLog.flush()
        }

        broadcastReceivedFromRadio(
            this,
            p
        )
    }

    /// Attempt to read from the fromRadio mailbox, if data is found broadcast it to android apps
    private fun doReadFromRadio() {
        if (!isConnected)
            warn("Abandoning fromradio read because we are not connected")
        else {
            val fromRadio = service.getCharacteristic(BTM_FROMRADIO_CHARACTER)
            safe!!.asyncReadCharacteristic(fromRadio) {
                val b = it.getOrThrow()
                    .value.clone() // We clone the array just in case, I'm not sure if they keep reusing the array

                if (b.isNotEmpty()) {
                    debug("Received ${b.size} bytes from radio")
                    handleFromRadio(b)

                    // Queue up another read, until we run out of packets
                    doReadFromRadio()
                } else {
                    debug("Done reading from radio, fromradio is empty")
                }
            }
        }
    }


    private fun onDisconnect() {
        broadcastConnectionChanged(false)
        isConnected = false
    }

    /**
     * Android caches old services.  But our service is still changing often, so force it to reread the service definitions every
     * time
     */
    private fun forceServiceRefresh() {
        exceptionReporter {
            // BluetoothGatt gatt
            val gatt = safe!!.gatt!!
            val refresh: Method = gatt.javaClass.getMethod("refresh")
            refresh.invoke(gatt)
        }
    }

    /// We only force service refresh the _first_ time we connect to the device.  Thereafter it is assumed the firmware didn't change
    private var hasForcedRefresh = false

    private fun onConnect(connRes: Result<Unit>) {
        // This callback is invoked after we are connected

        connRes.getOrThrow() // FIXME, instead just try to reconnect?
        info("Connected to radio!")

<<<<<<< HEAD
        if (!hasForcedRefresh) {
            // FIXME - for some reason we need to refresh _everytime_.  It is almost as if we've cached wrong descriptor fieldnums forever
            // hasForcedRefresh = true
            forceServiceRefresh()
        }
=======
        /// We gracefully handle safe being null because this can occur if someone has unpaired from our device - just abandon the reconnect attempt
        val s = safe
        if (s != null) {
            warn("Forcing disconnect and hopefully device will comeback (disabling forced refresh)")
            hasForcedRefresh = true
            ignoreException {
                s.closeConnection()
            }
            delay(1000) // Give some nasty time for buggy BLE stacks to shutdown (500ms was not enough)
            warn("Attempting reconnect")
            startConnect()
        } else {
            warn("Abandoning reconnect because safe==null, someone must have closed the device")
        }
    }
>>>>>>> upstream/master

        // FIXME - no need to discover services more than once - instead use lazy() to use them in future attempts
        safe!!.asyncDiscoverServices { discRes ->
            discRes.getOrThrow() // FIXME, instead just try to reconnect?
            debug("Discovered services! Service size=${service.characteristics.size}")

<<<<<<< HEAD
            // we begin by setting our MTU size as high as it can go
            safe!!.asyncRequestMtu(512) { mtuRes ->
                serviceScope.handledLaunch {
                    debug("requested MTU result=$mtuRes")
                    mtuRes.getOrThrow() // FIXME - why sometimes is the result Unit!?!
                    delay(500) // android BLE is buggy and needs a 500ms sleep before calling getChracteristic, or you might get back null

                    fromNum = service.getCharacteristic(BTM_FROMNUM_CHARACTER)!!
=======
            serviceScope.handledLaunch {
                try {
                    debug("Discovered services!")
                    delay(1000) // android BLE is buggy and needs a 500ms sleep before calling getChracteristic, or you might get back null

                    // service could be null, test this by throwing BLEException and testing it on my machine
                    if (isOldApi == null)
                        isOldApi = service.getCharacteristic(BTM_RADIO_CHARACTER) != null
                    warn("Use oldAPI = $isOldApi")

                    /* if (isFirstTime) {
                        isFirstTime = false
                        throw BLEException("Faking a BLE failure")
                    } */

                    fromNum = getCharacteristic(BTM_FROMNUM_CHARACTER)
>>>>>>> upstream/master

                    // We must set this to true before broadcasting connectionChanged
                    isConnected = true

                    safe!!.setNotify(fromNum, true) {
                        debug("fromNum changed, so we are reading new messages")
                        doReadFromRadio()
                    }

                    // Now tell clients they can (finally use the api)
                    broadcastConnectionChanged(true)

                    // Immediately broadcast any queued packets sitting on the device
                    doReadFromRadio()
                }
            }
        }
    }

    override fun onCreate() {
        runningService = this
        super.onCreate()
        setEnabled(true)
    }

    override fun onDestroy() {
        setEnabled(false)
        serviceJob.cancel()
        runningService = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return binder;
    }

<<<<<<< HEAD
=======
    /// Start a connection attempt
    private fun startConnect() {
        // we pass in true for autoconnect - so we will autoconnect whenever the radio
        // comes in range (even if we made this connect call long ago when we got powered on)
        // see https://stackoverflow.com/questions/40156699/which-correct-flag-of-autoconnect-in-connectgatt-of-ble for
        // more info
        safe!!.asyncConnect(true, // FIXME, sometimes this seems to not work or take a very long time!
            cb = ::onConnect,
            lostConnectCb = { onDisconnect(isPermanent = false) })
    }

>>>>>>> upstream/master
    /// Open or close a bluetooth connection to our device
    private fun setEnabled(on: Boolean) {
        if (on) {
            if (safe != null) {
                info("Skipping radio enable, it is already on")
            } else {
                val address = getBondedDeviceAddress(this)
                if (address == null)
                    errormsg("No bonded mesh radio, can't create service")
                else {
                    // Note: this call does no comms, it just creates the device object (even if the
                    // device is off/not connected)
                    val device = getBluetoothAdapter(this)?.getRemoteDevice(address)
                    if (device != null) {
<<<<<<< HEAD
                        info("Creating radio interface service.  device=$address")
=======
                        info("Creating radio interface service.  device=${address.anonymize}")
>>>>>>> upstream/master

                        // Note this constructor also does no comm
                        val s = SafeBluetooth(this, device)
                        safe = s

                        // FIXME, pass in true for autoconnect - so we will autoconnect whenever the radio
                        // comes in range (even if we made this connect call long ago when we got powered on)
                        // see https://stackoverflow.com/questions/40156699/which-correct-flag-of-autoconnect-in-connectgatt-of-ble for
                        // more info
                        s.asyncConnect(true, ::onConnect, ::onDisconnect)
                    } else {
                        errormsg("Bluetooth adapter not found, assuming running on the emulator!")
                    }

                    if (logSends)
                        sentPacketsLog = BinaryLogFile(this, "sent_log.pb")
                    if (logReceives)
                        receivedPacketsLog = BinaryLogFile(this, "receive_log.pb")
                }
            }
        } else {
<<<<<<< HEAD
            info("Closing radio interface service")
            if (logSends)
                sentPacketsLog.close()
            if (logReceives)
                receivedPacketsLog.close()
            safe?.close()
            safe = null
=======
            if (safe != null) {
                info("Closing radio interface service")
                val s = safe
                safe =
                    null // We do this first, because if we throw we still want to mark that we no longer have a valid connection

                if (logSends)
                    sentPacketsLog.close()
                if (logReceives)
                    receivedPacketsLog.close()
                s?.close()
                onDisconnect(isPermanent = true) // Tell any clients we are now offline
            } else {
                debug("Radio was not connected, skipping disable")
            }
>>>>>>> upstream/master
        }
    }

    /**
     * do a synchronous write operation
     */
    private fun doWrite(uuid: UUID, a: ByteArray) = toRemoteExceptions {
        if (!isConnected)
            throw RadioNotConnectedException()
        else {
            debug("queuing ${a.size} bytes to $uuid")

            // Note: we generate a new characteristic each time, because we are about to
            // change the data and we want the data stored in the closure
            val toRadio = service.getCharacteristic(uuid)
            toRadio.value = a

            safe!!.writeCharacteristic(toRadio)
            debug("write of ${a.size} bytes completed")
        }
    }

    /**
     * do an asynchronous write operation
     * Any error responses will be ignored (other than log messages)
     */
    private fun doAsyncWrite(uuid: UUID, a: ByteArray) = toRemoteExceptions {
        if (!isConnected)
            throw RadioNotConnectedException()
        else {
            debug("queuing ${a.size} bytes to $uuid")

            // Note: we generate a new characteristic each time, because we are about to
            // change the data and we want the data stored in the closure
            val toRadio = service.getCharacteristic(uuid)
            toRadio.value = a

            safe!!.asyncWriteCharacteristic(toRadio) {
                debug("asyncwrite of ${a.size} bytes completed")
            }
        }
    }

    /**
     * do a synchronous read operation
     */
    private fun doRead(uuid: UUID): ByteArray? = toRemoteExceptions {
        if (!isConnected)
            throw RadioNotConnectedException()
        else {
            // Note: we generate a new characteristic each time, because we are about to
            // change the data and we want the data stored in the closure
            val toRadio = service.getCharacteristic(uuid)
            var a = safe!!.readCharacteristic(toRadio)
                .value.clone() // we copy the bluetooth array because it might still be in use
            debug("Read of $uuid got ${a.size} bytes")

            if (a.isEmpty()) // An empty bluetooth response is converted to a null response for our clients
                null
            else
                a
        }
    }

    private val binder = object : IRadioInterfaceService.Stub() {
        override fun enableLink(enable: Boolean) = toRemoteExceptions {
            setEnabled(enable)
        }

        // A write of any size to nodeinfo means restart reading
        override fun restartNodeInfo() = doWrite(BTM_NODEINFO_CHARACTER, ByteArray(0))

        override fun readMyNode() =
            doRead(BTM_MYNODE_CHARACTER)
                ?: throw RemoteException("Device returned empty MyNodeInfo")

        override fun sendToRadio(a: ByteArray) = handleSendToRadio(a)

        override fun readRadioConfig() =
            doRead(BTM_RADIO_CHARACTER)
                ?: throw RemoteException("Device returned empty RadioConfig")

        override fun readOwner() =
            doRead(BTM_OWNER_CHARACTER) ?: throw RemoteException("Device returned empty Owner")

        override fun writeOwner(owner: ByteArray) = doWrite(BTM_OWNER_CHARACTER, owner)

        override fun writeRadioConfig(config: ByteArray) = doWrite(BTM_RADIO_CHARACTER, config)

        override fun readNodeInfo() = doRead(BTM_NODEINFO_CHARACTER)
    }
}
