package com.geeksville.mesh.service

import android.annotation.SuppressLint
import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import android.os.Parcelable
import android.os.RemoteException
import androidx.annotation.RequiresApi
import androidx.annotation.UiThread
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationCompat.PRIORITY_MIN
<<<<<<< HEAD
=======
import androidx.core.content.edit
import androidx.work.*
>>>>>>> upstream/master
import com.geeksville.analytics.DataPair
import com.geeksville.android.GeeksvilleApplication
import com.geeksville.android.Logging
import com.geeksville.android.ServiceClient
import com.geeksville.concurrent.handledLaunch
import com.geeksville.mesh.*
import com.geeksville.mesh.MeshProtos.MeshPacket
import com.geeksville.mesh.MeshProtos.ToRadio
import com.geeksville.util.Exceptions
import com.geeksville.util.exceptionReporter
import com.geeksville.util.toOneLineString
import com.geeksville.util.toRemoteExceptions
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.*
import com.google.protobuf.ByteString
import com.google.protobuf.InvalidProtocolBufferException
import kotlinx.coroutines.*
<<<<<<< HEAD
import java.nio.charset.Charset
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext


class RadioNotConnectedException() : Exception("Not connected to radio")


private val errorHandler = CoroutineExceptionHandler { _, exception ->
    Exceptions.report(exception, "MeshService-coroutine", "coroutine-exception")
}

/// Wrap launch with an exception handler, FIXME, move into a utility lib
fun CoroutineScope.handledLaunch(
    context: CoroutineContext = EmptyCoroutineContext,
    start: CoroutineStart = CoroutineStart.DEFAULT,
    block: suspend CoroutineScope.() -> Unit
) = this.launch(context = context + errorHandler, start = start, block = block)

=======
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonConfiguration
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.absoluteValue


>>>>>>> upstream/master
/**
 * Handles all the communication with android apps.  Also keeps an internal model
 * of the network state.
 *
 * Note: this service will go away once all clients are unbound from it.
 */
class MeshService : Service(), Logging {

    companion object : Logging {

        /// Intents broadcast by MeshService
        const val ACTION_RECEIVED_DATA = "$prefix.RECEIVED_DATA"
        const val ACTION_NODE_CHANGE = "$prefix.NODE_CHANGE"
        const val ACTION_MESH_CONNECTED = "$prefix.MESH_CONNECTED"
        const val ACTION_MESSAGE_STATUS = "$prefix.MESSAGE_STATUS"

        class IdNotFoundException(id: String) : Exception("ID not found $id")
        class NodeNumNotFoundException(id: Int) : Exception("NodeNum not found $id")
        // class NotInMeshException() : Exception("We are not yet in a mesh")

<<<<<<< HEAD
        /// Helper function to start running our service, returns the intent used to reach it
        /// or null if the service could not be started (no bluetooth or no bonded device set)
        fun startService(context: Context): Intent? {
            if (RadioInterfaceService.getBondedDeviceAddress(context) == null) {
                warn("No mesh radio is bonded, not starting service")
                return null
            } else {
                // bind to our service using the same mechanism an external client would use (for testing coverage)
                // The following would work for us, but not external users
                //val intent = Intent(this, MeshService::class.java)
                //intent.action = IMeshService::class.java.name
                val intent = Intent()
                intent.setClassName(
                    "com.geeksville.mesh",
                    "com.geeksville.mesh.service.MeshService"
                )

                // Before binding we want to explicitly create - so the service stays alive forever (so it can keep
                // listening for the bluetooth packets arriving from the radio.  And when they arrive forward them
                // to Signal or whatever.

                logAssert(
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }) != null
                )

                return intent
            }
=======
        /** A little helper that just calls startService
         */
        class ServiceStarter(appContext: Context, workerParams: WorkerParameters) :
            Worker(appContext, workerParams) {

            override fun doWork(): Result = try {
                startService(this.applicationContext)

                // Indicate whether the task finished successfully with the Result
                Result.success()
            } catch (ex: Exception) {
                errormsg("failure starting service, will retry", ex)
                Result.retry()
            }
        }

        /**
         * Just after boot the android OS is super busy, so if we call startForegroundService then, our
         * thread might be stalled long enough to expose this google/samsung bug:
         * https://issuetracker.google.com/issues/76112072#comment56
         */
        fun startLater(context: Context) {
            info("Received boot complete announcement, starting mesh service in one minute")
            val delayRequest = OneTimeWorkRequestBuilder<ServiceStarter>()
                .setInitialDelay(1, TimeUnit.MINUTES)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .addTag("startLater")
                .build()

            WorkManager.getInstance(context).enqueue(delayRequest)
        }

        val intent = Intent().apply {
            setClassName(
                "com.geeksville.mesh",
                "com.geeksville.mesh.service.MeshService"
            )
        }

        /// Helper function to start running our service
        fun startService(context: Context) {
            // bind to our service using the same mechanism an external client would use (for testing coverage)
            // The following would work for us, but not external users
            //val intent = Intent(this, MeshService::class.java)
            //intent.action = IMeshService::class.java.name


            // Before binding we want to explicitly create - so the service stays alive forever (so it can keep
            // listening for the bluetooth packets arriving from the radio.  And when they arrive forward them
            // to Signal or whatever.

            info("Trying to start service")
            val compName =
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                })

            if (compName == null)
                throw Exception("Failed to start service")
>>>>>>> upstream/master
        }

        /// A model object for a Text message
        data class TextMessage(val fromId: String, val text: String)
    }

    public enum class ConnectionState {
        DISCONNECTED,
        CONNECTED,
        DEVICE_SLEEP // device is in LS sleep state, it will reconnected to us over bluetooth once it has data
    }

    /// A mapping of receiver class name to package name - used for explicit broadcasts
    private val clientPackages = mutableMapOf<String, String>()

    val radio = ServiceClient {
        IRadioInterfaceService.Stub.asInterface(it)
    }

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    /// The current state of our connection
    private var connectionState = ConnectionState.DISCONNECTED

    /*
    see com.geeksville.mesh broadcast intents
    // RECEIVED_OPAQUE  for data received from other nodes
    // NODE_CHANGE  for new IDs appearing or disappearing
    // ACTION_MESH_CONNECTED for losing/gaining connection to the packet radio (note, this is not
    the same as RadioInterfaceService.RADIO_CONNECTED_ACTION, because it implies we have assembled a valid
    node db.
     */

    private fun explicitBroadcast(intent: Intent) {
        sendBroadcast(intent) // We also do a regular (not explicit broadcast) so any context-registered rceivers will work
        clientPackages.forEach {
            intent.setClassName(it.value, it.key)
            sendBroadcast(intent)
        }
    }

    private val locationCallback = object : LocationCallback() {
        private var lastSendMsec = 0L

        override fun onLocationResult(locationResult: LocationResult) {
            serviceScope.handledLaunch {
                super.onLocationResult(locationResult)
                var l = locationResult.lastLocation

                // Docs say lastLocation should always be !null if there are any locations, but that's not the case
                if (l == null) {
                    // try to only look at the accurate locations
                    val locs =
                        locationResult.locations.filter { !it.hasAccuracy() || it.accuracy < 200 }
                    l = locs.lastOrNull()
                }
                if (l != null) {
                    info("got phone location")
                    if (l.hasAccuracy() && l.accuracy >= 200) // if more than 200 meters off we won't use it
                        warn("accuracy ${l.accuracy} is too poor to use")
                    else {
                        val now = System.currentTimeMillis()

                        // we limit our sends onto the lora net to a max one once every FIXME
                        val sendLora = (now - lastSendMsec >= 30 * 1000)
                        if (sendLora)
                            lastSendMsec = now
                        try {
                            sendPosition(
                                l.latitude, l.longitude, l.altitude.toInt(),
                                destNum = if (sendLora) NODENUM_BROADCAST else myNodeNum,
                                wantResponse = sendLora
                            )
                        } catch (ex: RadioNotConnectedException) {
                            warn("Lost connection to radio, stopping location requests")
                            onConnectionChanged(ConnectionState.DEVICE_SLEEP)
                        } catch (ex: BLEException) { // Really a RadioNotConnected exception, but it has changed into this type via remoting
                            warn("BLE exception, stopping location requests $ex")
                            onConnectionChanged(ConnectionState.DEVICE_SLEEP)
                        }
                    }
                }
            }
        }
    }

    private var fusedLocationClient: FusedLocationProviderClient? = null

    /**
     * start our location requests (if they weren't already running)
     *
     * per https://developer.android.com/training/location/change-location-settings
     */
    @SuppressLint("MissingPermission")
    @UiThread
    private fun startLocationRequests() {
        if (fusedLocationClient == null) {
            GeeksvilleApplication.analytics.track("location_start") // Figure out how many users needed to use the phone GPS

            val request = LocationRequest.create().apply {
                interval =
                    5 * 60 * 1000 // FIXME, do more like once every 5 mins while we are connected to our radio _and_ someone else is in the mesh

                priority = LocationRequest.PRIORITY_HIGH_ACCURACY
            }
            val builder = LocationSettingsRequest.Builder().addLocationRequest(request)
            val locationClient = LocationServices.getSettingsClient(this)
            val locationSettingsResponse = locationClient.checkLocationSettings(builder.build())

            locationSettingsResponse.addOnSuccessListener {
                debug("We are now successfully listening to the GPS")
            }

            locationSettingsResponse.addOnFailureListener { exception ->
                errormsg("Failed to listen to GPS")
                if (exception is ResolvableApiException) {
                    Exceptions.report(exception) // FIXME, not yet implemented, report failure to mothership
                    exceptionReporter {
                        // Location settings are not satisfied, but this can be fixed
                        // by showing the user a dialog.

                        // FIXME
                        // Show the dialog by calling startResolutionForResult(),
                        // and check the result in onActivityResult().
                        /* exception.startResolutionForResult(
                            this@MainActivity,
                            REQUEST_CHECK_SETTINGS
                        ) */
                    }
                } else
                    Exceptions.report(exception)
            }

            val client = LocationServices.getFusedLocationProviderClient(this)

            // FIXME - should we use Looper.myLooper() in the third param per https://github.com/android/location-samples/blob/432d3b72b8c058f220416958b444274ddd186abd/LocationUpdatesForegroundService/app/src/main/java/com/google/android/gms/location/sample/locationupdatesforegroundservice/LocationUpdatesService.java
            client.requestLocationUpdates(request, locationCallback, null)

            fusedLocationClient = client
        }
    }

    private fun stopLocationRequests() {
        if (fusedLocationClient != null) {
            debug("Stopping location requests")
            GeeksvilleApplication.analytics.track("location_stop")
            fusedLocationClient?.removeLocationUpdates(locationCallback)
            fusedLocationClient = null
        }
    }

    /**
     * The RECEIVED_OPAQUE:
     * Payload will be the raw bytes which were contained within a MeshPacket.Opaque field
     * Sender will be a user ID string
     * Type will be the Data.Type enum code for this payload
     */
    private fun broadcastReceivedData(senderId: String, payload: ByteArray, typ: Int) {
        val intent = Intent(ACTION_RECEIVED_DATA)
        intent.putExtra(EXTRA_SENDER, senderId)
        intent.putExtra(EXTRA_PAYLOAD, payload)
        intent.putExtra(EXTRA_TYP, typ)
        explicitBroadcast(intent)
    }

    private fun broadcastNodeChange(info: NodeInfo) {
        debug("Broadcasting node change $info")
        val intent = Intent(ACTION_NODE_CHANGE)

        intent.putExtra(EXTRA_NODEINFO, info)
        explicitBroadcast(intent)
    }

    private fun broadcastMessageStatus(p: DataPacket) {
        if (p.id == 0) {
            debug("Ignoring anonymous packet status")
        } else {
            debug("Broadcasting message status $p")
            val intent = Intent(ACTION_MESSAGE_STATUS)

            intent.putExtra(EXTRA_PACKET_ID, p.id)
            intent.putExtra(EXTRA_STATUS, p.status as Parcelable)
            explicitBroadcast(intent)
        }
    }

    /// Safely access the radio service, if not connected an exception will be thrown
    private val connectedRadio: IRadioInterfaceService
        get() = (if (connectionState == ConnectionState.CONNECTED) radio.serviceP else null)
            ?: throw RadioNotConnectedException()

    /// Send a command/packet to our radio.  But cope with the possiblity that we might start up
    /// before we are fully bound to the RadioInterfaceService
    private fun sendToRadio(p: ToRadio.Builder) {
        val b = p.build().toByteArray()

        connectedRadio.sendToRadio(b)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return binder
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel(): String {
        val channelId = "my_service"
        val channelName = "My Background Service"
        val chan = NotificationChannel(
            channelId,
            channelName, NotificationManager.IMPORTANCE_HIGH
        )
        chan.lightColor = Color.BLUE
        chan.importance = NotificationManager.IMPORTANCE_NONE
        chan.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        notificationManager.createNotificationChannel(chan)
        return channelId
    }

    private val notifyId = 101
    val notificationManager: NotificationManager by lazy() {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    /// This must be lazy because we use Context
    private val channelId: String by lazy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel()
        } else {
            // If earlier version channel ID is not used
            // https://developer.android.com/reference/android/support/v4/app/NotificationCompat.Builder.html#NotificationCompat.Builder(android.content.Context)
            ""
        }
    }

    private val openAppIntent: PendingIntent by lazy() {
        PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), 0)
    }

    /// A text message that has a arrived since the last notification update
    private var recentReceivedText: TextMessage? = null

    private val summaryString
        get() = when (connectionState) {
            ConnectionState.CONNECTED -> "Connected: $numOnlineNodes of $numNodes online"
            ConnectionState.DISCONNECTED -> "Disconnected"
            ConnectionState.DEVICE_SLEEP -> "Device sleeping"
        }


    override fun toString() = summaryString

    /**
     * Generate a new version of our notification - reflecting current app state
     */
    private fun createNotification(): Notification {

        val notificationBuilder = NotificationCompat.Builder(this, channelId)

        val builder = notificationBuilder.setOngoing(true)
            .setPriority(PRIORITY_MIN)
            .setCategory(if (recentReceivedText != null) Notification.CATEGORY_SERVICE else Notification.CATEGORY_MESSAGE)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(summaryString) // leave this off for now so our notification looks smaller
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openAppIntent)

        // FIXME, show information about the nearest node
        // if(shortContent != null) builder.setContentText(shortContent)

        // If a text message arrived include it with our notification
        recentReceivedText?.let { msg ->
            builder.setContentText("Message from ${msg.fromId}")

            builder.setStyle(
                NotificationCompat.BigTextStyle()
<<<<<<< HEAD
                    .bigText(msg.text)
=======
                    .bigText(packet.bytes!!.toString(utf8))
>>>>>>> upstream/master
            )
        }

        return builder.build()
    }

    /**
     * Update our notification with latest data
     */
    private fun updateNotification() {
        notificationManager.notify(notifyId, createNotification())
    }

    /**
     * tell android not to kill us
     */
    private fun startForeground() {
        startForeground(notifyId, createNotification())
    }

    override fun onCreate() {
        super.onCreate()

        info("Creating mesh service")
        startForeground()

        // we listen for messages from the radio receiver _before_ trying to create the service
        val filter = IntentFilter()
        filter.addAction(RadioInterfaceService.RECEIVE_FROMRADIO_ACTION)
        filter.addAction(RadioInterfaceService.RADIO_CONNECTED_ACTION)
        registerReceiver(radioInterfaceReceiver, filter)

        // We in turn need to use the radiointerface service
        val intent = Intent(this, RadioInterfaceService::class.java)
        // intent.action = IMeshService::class.java.name
        radio.connect(this, intent, Context.BIND_AUTO_CREATE)

        // the rest of our init will happen once we are in radioConnection.onServiceConnected
    }


    override fun onDestroy() {
        info("Destroying mesh service")
        unregisterReceiver(radioInterfaceReceiver)
        radio.close()

        super.onDestroy()
        serviceJob.cancel()
    }


    ///
    /// BEGINNING OF MODEL - FIXME, move elsewhere
    ///

    /// special broadcast address
    val NODENUM_BROADCAST = 255

<<<<<<< HEAD
    // MyNodeInfo sent via special protobuf from radio
    data class MyNodeInfo(
        val myNodeNum: Int,
        val hasGPS: Boolean,
        val region: String,
        val model: String,
        val firmwareVersion: String
    )
=======
    /// Our saved preferences as stored on disk
    @Serializable
    private data class SavedSettings(
        val nodeDB: Array<NodeInfo>,
        val myInfo: MyNodeInfo,
        val messages: Array<DataPacket>
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as SavedSettings

            if (!nodeDB.contentEquals(other.nodeDB)) return false
            if (myInfo != other.myInfo) return false
            if (!messages.contentEquals(other.messages)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = nodeDB.contentHashCode()
            result = 31 * result + myInfo.hashCode()
            result = 31 * result + messages.contentHashCode()
            return result
        }
    }

    private fun getPrefs() = getSharedPreferences("service-prefs", Context.MODE_PRIVATE)

    /// Save information about our mesh to disk, so we will have it when we next start the service (even before we hear from our device)
    private fun saveSettings() {
        myNodeInfo?.let { myInfo ->
            val settings = SavedSettings(
                myInfo = myInfo,
                nodeDB = nodeDBbyNodeNum.values.toTypedArray(),
                messages = recentDataPackets.toTypedArray()
            )
            val json = Json(JsonConfiguration.Default)
            val asString = json.stringify(SavedSettings.serializer(), settings)
            debug("Saving settings as $asString")
            getPrefs().edit(commit = true) {
                // FIXME, not really ideal to store this bigish blob in preferences
                putString("json", asString)
            }
        }
    }

    /**
     * Install a new node DB
     */
    private fun installNewNodeDB(newMyNodeInfo: MyNodeInfo, nodes: Array<NodeInfo>) {
        discardNodeDB() // Get rid of any old state

        myNodeInfo = newMyNodeInfo

        // put our node array into our two different map representations
        nodeDBbyNodeNum.putAll(nodes.map { Pair(it.num, it) })
        nodeDBbyID.putAll(nodes.mapNotNull {
            it.user?.let { user -> // ignore records that don't have a valid user
                Pair(
                    user.id,
                    it
                )
            }
        })
    }

    /// Load our saved DB state
    private fun loadSettings() {
        try {
            getPrefs().getString("json", null)?.let { asString ->

                val json = Json(JsonConfiguration.Default)
                val settings = json.parse(SavedSettings.serializer(), asString)
                installNewNodeDB(settings.myInfo, settings.nodeDB)

                // Note: we do not haveNodeDB = true because that means we've got a valid db from a real device (rather than this possibly stale hint)

                recentDataPackets.addAll(settings.messages)
            }
        } catch (ex: Exception) {
            errormsg("Ignoring error loading saved state for service: ${ex.message}")
        }
    }

    /**
     * discard entire node db & message state - used when changing radio channels
     */
    private fun discardNodeDB() {
        myNodeInfo = null
        nodeDBbyNodeNum.clear()
        nodeDBbyID.clear()
        recentDataPackets.clear()
        haveNodeDB = false
    }
>>>>>>> upstream/master

    var myNodeInfo: MyNodeInfo? = null

    private var radioConfig: MeshProtos.RadioConfig? = null

    /// True after we've done our initial node db init
    private var haveNodeDB = false

    // The database of active nodes, index is the node number
    private val nodeDBbyNodeNum = mutableMapOf<Int, NodeInfo>()

    /// The database of active nodes, index is the node user ID string
    /// NOTE: some NodeInfos might be in only nodeDBbyNodeNum (because we don't yet know
    /// an ID).  But if a NodeInfo is in both maps, it must be one instance shared by
    /// both datastructures.
    private val nodeDBbyID = mutableMapOf<String, NodeInfo>()

    ///
    /// END OF MODEL
    ///

    /// Map a nodenum to a node, or throw an exception if not found
    private fun toNodeInfo(n: Int) = nodeDBbyNodeNum[n] ?: throw NodeNumNotFoundException(
        n
    )

<<<<<<< HEAD
    /// Map a nodenum to the nodeid string, or throw an exception if not present
    private fun toNodeID(n: Int) = toNodeInfo(n).user?.id
=======
    /// Map a nodenum to the nodeid string, or return null if not present or no id found
    private fun toNodeID(n: Int) =
        if (n == NODENUM_BROADCAST) DataPacket.ID_BROADCAST else nodeDBbyNodeNum[n]?.user?.id
>>>>>>> upstream/master

    /// given a nodenum, return a db entry - creating if necessary
    private fun getOrCreateNodeInfo(n: Int) =
        nodeDBbyNodeNum.getOrPut(n) { -> NodeInfo(n) }

    /// Map a userid to a node/ node num, or throw an exception if not found
    private fun toNodeInfo(id: String) =
        nodeDBbyID[id]
            ?: throw IdNotFoundException(
                id
            )


    private val numNodes get() = nodeDBbyNodeNum.size

    /**
     * How many nodes are currently online (including our local node)
     */
    private val numOnlineNodes get() = nodeDBbyNodeNum.values.count { it.isOnline }

    private fun toNodeNum(id: String) =
        when (id) {
            DataPacket.ID_BROADCAST -> NODENUM_BROADCAST
            DataPacket.ID_LOCAL -> myNodeNum
            else -> toNodeInfo(id).num
        }

    /// A helper function that makes it easy to update node info objects
    private fun updateNodeInfo(nodeNum: Int, updatefn: (NodeInfo) -> Unit) {
        val info = getOrCreateNodeInfo(nodeNum)
        updatefn(info)

        // This might have been the first time we know an ID for this node, so also update the by ID map
        val userId = info.user?.id.orEmpty()
        if (userId.isNotEmpty())
            nodeDBbyID[userId] = info

        // parcelable is busted
        broadcastNodeChange(info)
    }

    /// My node num
    private val myNodeNum get() = myNodeInfo!!.myNodeNum

    /// My node ID string
    private val myNodeID get() = toNodeID(myNodeNum)

    /// Generate a new mesh packet builder with our node as the sender, and the specified node num
    private fun newMeshPacketTo(idNum: Int) = MeshPacket.newBuilder().apply {
        if (myNodeInfo == null)
            throw RadioNotConnectedException()

        from = myNodeNum
        to = idNum
    }

    /**
     * Generate a new mesh packet builder with our node as the sender, and the specified recipient
     *
     * If id is null we assume a broadcast message
     */
    private fun newMeshPacketTo(id: String) =
        newMeshPacketTo(toNodeNum(id))

    /**
     * Helper to make it easy to build a subpacket in the proper protobufs
     *
     * If destId is null we assume a broadcast message
     */
    private fun buildMeshPacket(
        destId: String,
        wantAck: Boolean = false,
        id: Int = 0,
        initFn: MeshProtos.SubPacket.Builder.() -> Unit
    ): MeshPacket = newMeshPacketTo(destId).apply {
<<<<<<< HEAD
        payload = MeshProtos.SubPacket.newBuilder().also {
=======
        this.wantAck = wantAck
        this.id = id
        decoded = MeshProtos.SubPacket.newBuilder().also {
>>>>>>> upstream/master
            initFn(it)
        }.build()
    }.build()

<<<<<<< HEAD
    /// Update our model and resend as needed for a MeshPacket we just received from the radio
    private fun handleReceivedData(fromNum: Int, data: MeshProtos.Data) {
        val bytes = data.payload.toByteArray()
        val fromId = toNodeID(fromNum)

        /// the sending node ID if possible, else just its number
        val fromString = fromId ?: fromId.toString()

        fun forwardData() {
            if (fromId == null)
                warn("Ignoring data from $fromNum because we don't yet know its ID")
            else {
                debug("Received data from $fromId ${bytes.size}")
                broadcastReceivedData(fromId, bytes, data.typValue)
            }
        }

        when (data.typValue) {
            MeshProtos.Data.Type.CLEAR_TEXT_VALUE -> {
                val text = bytes.toString(Charset.forName("UTF-8"))

                debug("Received CLEAR_TEXT from $fromString")

                recentReceivedText = TextMessage(fromString, text)
                updateNotification()
                forwardData()
            }

            MeshProtos.Data.Type.CLEAR_READACK_VALUE ->
                warn(
                    "TODO ignoring CLEAR_READACK from $fromString"
                )

            MeshProtos.Data.Type.SIGNAL_OPAQUE_VALUE ->
                forwardData()

            else -> TODO()
=======
    // FIXME - possible kotlin bug in 1.3.72 - it seems that if we start with the (globally shared) emptyList,
    // then adding items are affecting that shared list rather than a copy.   This was causing aliasing of
    // recentDataPackets with messages.value in the GUI.  So if the current list is empty we are careful to make a new list
    private var recentDataPackets = mutableListOf<DataPacket>()

    /// Generate a DataPacket from a MeshPacket, or null if we didn't have enough data to do so
    private fun toDataPacket(packet: MeshPacket): DataPacket? {
        return if (!packet.hasDecoded() || !packet.decoded.hasData()) {
            // We never convert packets that are not DataPackets
            null
        } else {
            val data = packet.decoded.data
            val bytes = data.payload.toByteArray()
            val fromId = toNodeID(packet.from)
            val toId = toNodeID(packet.to)

            // If the rxTime was not set by the device (because device software was old), guess at a time
            val rxTime = if (packet.rxTime == 0) packet.rxTime else currentSecond()

            when {
                fromId == null -> {
                    errormsg("Ignoring data from ${packet.from} because we don't yet know its ID")
                    null
                }
                toId == null -> {
                    errormsg("Ignoring data to ${packet.to} because we don't yet know its ID")
                    null
                }
                else -> {
                    DataPacket(
                        from = fromId,
                        to = toId,
                        time = rxTime * 1000L,
                        id = packet.id,
                        dataType = data.typValue,
                        bytes = bytes
                    )
                }
            }
        }
    }

    private fun toMeshPacket(p: DataPacket): MeshPacket {
        return buildMeshPacket(p.to!!, id = p.id, wantAck = true) {
            data = MeshProtos.Data.newBuilder().also {
                it.typ = MeshProtos.Data.Type.forNumber(p.dataType)
                it.payload = ByteString.copyFrom(p.bytes)
            }.build()
        }
    }

    private fun rememberDataPacket(dataPacket: DataPacket) {
        // discard old messages if needed then add the new one
        while (recentDataPackets.size > 50)
            recentDataPackets.removeAt(0)

        // FIXME - possible kotlin bug in 1.3.72 - it seems that if we start with the (globally shared) emptyList,
        // then adding items are affecting that shared list rather than a copy.   This was causing aliasing of
        // recentDataPackets with messages.value in the GUI.  So if the current list is empty we are careful to make a new list
        if (recentDataPackets.isEmpty())
            recentDataPackets = mutableListOf(dataPacket)
        else
            recentDataPackets.add(dataPacket)
    }

    /// Update our model and resend as needed for a MeshPacket we just received from the radio
    private fun handleReceivedData(packet: MeshPacket) {
        myNodeInfo?.let { myInfo ->
            val data = packet.decoded.data
            val bytes = data.payload.toByteArray()
            val fromId = toNodeID(packet.from)
            val dataPacket = toDataPacket(packet)

            if (dataPacket != null) {

                if (myInfo.myNodeNum == packet.from)
                    debug("Ignoring retransmission of our packet ${bytes.size}")
                else {
                    debug("Received data from $fromId ${bytes.size}")

                    dataPacket.status = MessageStatus.RECEIVED
                    rememberDataPacket(dataPacket)

                    when (data.typValue) {
                        MeshProtos.Data.Type.CLEAR_TEXT_VALUE -> {
                            debug("Received CLEAR_TEXT from $fromId")

                            recentReceivedText = dataPacket
                            updateNotification()
                            broadcastReceivedData(dataPacket)
                        }

                        MeshProtos.Data.Type.CLEAR_READACK_VALUE ->
                            warn(
                                "TODO ignoring CLEAR_READACK from $fromId"
                            )

                        MeshProtos.Data.Type.OPAQUE_VALUE ->
                            broadcastReceivedData(dataPacket)

                        else -> TODO()
                    }

                    GeeksvilleApplication.analytics.track(
                        "num_data_receive",
                        DataPair(1)
                    )

                    GeeksvilleApplication.analytics.track(
                        "data_receive",
                        DataPair("num_bytes", bytes.size),
                        DataPair("type", data.typValue)
                    )
                }
            }
>>>>>>> upstream/master
        }

        GeeksvilleApplication.analytics.track(
            "data_receive",
            DataPair("num_bytes", bytes.size),
            DataPair("type", data.typValue)
        )
    }

    /// Update our DB of users based on someone sending out a User subpacket
    private fun handleReceivedUser(fromNum: Int, p: MeshProtos.User) {
        updateNodeInfo(fromNum) {
            val oldId = it.user?.id.orEmpty()
            it.user = MeshUser(
                if (p.id.isNotEmpty()) p.id else oldId, // If the new update doesn't contain an ID keep our old value
                p.longName,
                p.shortName
            )
        }
    }

    /// Update our DB of users based on someone sending out a Position subpacket
    private fun handleReceivedPosition(fromNum: Int, p: MeshProtos.Position) {
        updateNodeInfo(fromNum) {
            it.position = Position(
                p.latitude,
                p.longitude,
                p.altitude,
                if (p.time != 0) p.time else it.position?.time
                    ?: 0 // if this position didn't include time, just keep our old one
            )
        }
    }

    /// If packets arrive before we have our node DB, we delay parsing them until the DB is ready
    private val earlyReceivedPackets = mutableListOf<MeshPacket>()

    /// If apps try to send packets when our radio is sleeping, we queue them here instead
    private val offlineSentPackets = mutableListOf<DataPacket>()

    /** Keep a record of recently sent packets, so we can properly handle ack/nak */
    private val sentPackets = mutableMapOf<Int, DataPacket>()

    /// Update our model and resend as needed for a MeshPacket we just received from the radio
    private fun handleReceivedMeshPacket(packet: MeshPacket) {
        if (haveNodeDB) {
            processReceivedMeshPacket(packet)
            onNodeDBChanged()
        } else {
            earlyReceivedPackets.add(packet)
            logAssert(earlyReceivedPackets.size < 128) // The max should normally be about 32, but if the device is messed up it might try to send forever
        }
    }

    /// Process any packets that showed up too early
    private fun processEarlyPackets() {
        earlyReceivedPackets.forEach { processReceivedMeshPacket(it) }
        earlyReceivedPackets.clear()

<<<<<<< HEAD
        offlineSentPackets.forEach { sendMeshPacket(it) }
=======
        offlineSentPackets.forEach { p ->
            // encapsulate our payload in the proper protobufs and fire it off
            val packet = toMeshPacket(p)
            p.status = MessageStatus.ENROUTE
            p.time =
                System.currentTimeMillis() // update time to the actual time we started sending
            sendToRadio(packet)
            broadcastMessageStatus(p)
        }
>>>>>>> upstream/master
        offlineSentPackets.clear()
    }


    /**
     * Handle an ack/nak packet by updating sent message status
     */
    private fun handleAckNak(isAck: Boolean, id: Int) {
        sentPackets.remove(id)?.let { p ->
            p.status = if (isAck) MessageStatus.DELIVERED else MessageStatus.ERROR
            broadcastMessageStatus(p)
        }
    }

    /// Update our model and resend as needed for a MeshPacket we just received from the radio
    private fun processReceivedMeshPacket(packet: MeshPacket) {
        val fromNum = packet.from

        // FIXME, perhaps we could learn our node ID by looking at any to packets the radio
        // decided to pass through to us (except for broadcast packets)
        //val toNum = packet.to

        val p = packet.payload

        // Update last seen for the node that sent the packet, but also for _our node_ because anytime a packet passes
        // through our node on the way to the phone that means that local node is also alive in the mesh
        updateNodeInfo(fromNum) {
            // Update our last seen based on any valid timestamps.  If the device didn't provide a timestamp make one
            val lastSeen =
                if (packet.rxTime != 0) packet.rxTime else currentSecond()

            it.position = it.position?.copy(time = lastSeen)
        }
        updateNodeInfo(myNodeNum) {
            it.position = it.position?.copy(time = currentSecond())
        }

        when (p.variantCase.number) {
            MeshProtos.SubPacket.POSITION_FIELD_NUMBER ->
                handleReceivedPosition(fromNum, p.position)

            MeshProtos.SubPacket.DATA_FIELD_NUMBER ->
                handleReceivedData(fromNum, p.data)

<<<<<<< HEAD
            MeshProtos.SubPacket.USER_FIELD_NUMBER ->
                handleReceivedUser(fromNum, p.user)
            else -> TODO("Unexpected SubPacket variant")
        }
=======
        if (p.hasUser())
            handleReceivedUser(fromNum, p.user)

        if (p.successId != 0)
            handleAckNak(true, p.successId)

        if (p.failId != 0)
            handleAckNak(false, p.failId)
>>>>>>> upstream/master
    }

    private fun currentSecond() = (System.currentTimeMillis() / 1000).toInt()


    /// We are reconnecting to a radio, redownload the full state.  This operation might take hundreds of milliseconds
    private fun reinitFromRadio() {
        // Read the MyNodeInfo object
        val myInfo = MeshProtos.MyNodeInfo.parseFrom(
            connectedRadio.readMyNode()
        )

        handleMyInfo(myInfo)
        myNodeInfo = newMyNodeInfo // Apply the changes from handleMyInfo right now

        radioConfig = MeshProtos.RadioConfig.parseFrom(connectedRadio.readRadioConfig())

        // Ask for the current node DB
        connectedRadio.restartNodeInfo()

        // read all the infos until we get back null
        var infoBytes = connectedRadio.readNodeInfo()
        while (infoBytes != null) {
            val info =
                MeshProtos.NodeInfo.parseFrom(infoBytes)
            debug("Received initial nodeinfo num=${info.num}, hasUser=${info.hasUser()}, hasPosition=${info.hasPosition()}")

            // Just replace/add any entry
            updateNodeInfo(info.num) {
                if (info.hasUser())
                    it.user =
                        MeshUser(
                            info.user.id,
                            info.user.longName,
                            info.user.shortName
                        )

                if (info.hasPosition()) {
                    // For the local node, it might not be able to update its times because it doesn't have a valid GPS reading yet
                    // so if the info is for _our_ node we always assume time is current
                    val time =
                        if (it.num == mi.myNodeNum) currentSecond() else info.position.time

                    it.position = Position(
                        info.position.latitude,
                        info.position.longitude,
                        info.position.altitude,
                        time
                    )
                }
            }

            // advance to next
            infoBytes = connectedRadio.readNodeInfo()
        }

        haveNodeDB = true // we've done our initial node db initialization
        processEarlyPackets() // handle any packets that showed up while we were booting

        // broadcast an intent with our new connection state
        broadcastConnection()
        reportConnection() // this is just analytics
        onNodeDBChanged()
    }

    /// If we just changed our nodedb, we might want to do somethings
    private fun onNodeDBChanged() {
        updateNotification()

        // we don't ask for GPS locations from android if our device has a built in GPS
        // Note: myNodeInfo can go away if we lose connections, so it might be null
        if (myNodeInfo?.hasGPS != true) {
            // If we have at least one other person in the mesh, send our GPS position otherwise stop listening to GPS

            serviceScope.handledLaunch(Dispatchers.Main) {
                if (numOnlineNodes >= 2)
                    startLocationRequests()
                else
                    stopLocationRequests()
            }
        } else
            debug("Our radio has a built in GPS, so not reading GPS in phone")
    }


    private var sleepTimeout: Job? = null

    /// Called when we gain/lose connection to our radio
    private fun onConnectionChanged(c: ConnectionState) {
        debug("onConnectionChanged=$c")

        /// Perform all the steps needed once we start waiting for device sleep to complete
        fun startDeviceSleep() {
            // lost radio connection, therefore no need to keep listening to GPS
            stopLocationRequests()

            // Have our timeout fire in the approprate number of seconds
            sleepTimeout = serviceScope.handledLaunch {
                try {
                    // If we have a valid timeout, wait that long (+30 seconds) otherwise, just wait 30 seconds
                    val timeout = (radioConfig?.preferences?.lsSecs ?: 0) + 30

                    debug("Waiting for sleeping device, timeout=$timeout secs")
                    delay(timeout * 1000L)
                    warn("Device timeout out, setting disconnected")
                    onConnectionChanged(ConnectionState.DISCONNECTED)
                } catch (ex: CancellationException) {
                    debug("device sleep timeout cancelled")
                }
            }

            // broadcast an intent with our new connection state
            broadcastConnection()
        }

        fun startDisconnect() {
            GeeksvilleApplication.analytics.track(
                "mesh_disconnect",
                DataPair("num_nodes", numNodes),
                DataPair("num_online", numOnlineNodes)
            )
<<<<<<< HEAD
=======
            GeeksvilleApplication.analytics.track("num_nodes", DataPair(numNodes))

            // broadcast an intent with our new connection state
            broadcastConnection()
>>>>>>> upstream/master
        }

        fun startConnect() {
            // Do our startup init
            try {
                reinitFromRadio()

                val radioModel = DataPair("radio_model", myNodeInfo?.model ?: "unknown")
                GeeksvilleApplication.analytics.track(
                    "mesh_connect",
                    DataPair("num_nodes", numNodes),
                    DataPair("num_online", numOnlineNodes),
                    radioModel
                )

<<<<<<< HEAD
                // Once someone connects to hardware start tracking the approximate number of nodes in their mesh
                // this allows us to collect stats on what typical mesh size is and to tell difference between users who just
                // downloaded the app, vs has connected it to some hardware.
                GeeksvilleApplication.analytics.setUserInfo(
                    DataPair("num_nodes", numNodes),
                    radioModel
                )
=======
            } catch (ex: InvalidProtocolBufferException) {
                errormsg(
                    "Invalid protocol buffer sent by device - update device software and try again",
                    ex
                )
            } catch (ex: RadioNotConnectedException) {
                // note: no need to call startDeviceSleep(), because this exception could only have reached us if it was already called
                errormsg("Lost connection to radio during init - waiting for reconnect")
>>>>>>> upstream/master
            } catch (ex: RemoteException) {
                // It seems that when the ESP32 goes offline it can briefly come back for a 100ms ish which
                // causes the phone to try and reconnect.  If we fail downloading our initial radio state we don't want to
                // claim we have a valid connection still
                connectionState = ConnectionState.DEVICE_SLEEP
                startDeviceSleep()
                throw ex; // Important to rethrow so that we don't tell the app all is well
            }
        }

        // Cancel any existing timeouts
        sleepTimeout?.let {
            it.cancel()
            sleepTimeout = null
        }

        connectionState = c
        when (c) {
            ConnectionState.CONNECTED ->
                startConnect()
            ConnectionState.DEVICE_SLEEP ->
                startDeviceSleep()
            ConnectionState.DISCONNECTED ->
                startDisconnect()
        }

        // Update the android notification in the status bar
        updateNotification()
    }

    /**
     * broadcast our current connection status
     */
    private fun broadcastConnection() {
        val intent = Intent(ACTION_MESH_CONNECTED)
        intent.putExtra(
            EXTRA_CONNECTED,
            connectionState.toString()
        )
        explicitBroadcast(intent)
    }

    /**
     * Receives messages from our BT radio service and processes them to update our model
     * and send to clients as needed.
     */
    private val radioInterfaceReceiver = object : BroadcastReceiver() {

        // Important to never throw exceptions out of onReceive
        override fun onReceive(context: Context, intent: Intent) = exceptionReporter {
            serviceScope.handledLaunch {
                debug("Received broadcast ${intent.action}")
                when (intent.action) {
                    RadioInterfaceService.RADIO_CONNECTED_ACTION -> {
                        try {
                            onConnectionChanged(
                                if (intent.getBooleanExtra(EXTRA_CONNECTED, false))
                                    ConnectionState.CONNECTED
                                else
                                    ConnectionState.DEVICE_SLEEP
                            )
                        } catch (ex: RemoteException) {
                            // This can happen sometimes (especially if the device is slowly dying due to killing power, don't report to crashlytics
                            warn("Abandoning reconnect attempt, due to errors during init: ${ex.message}")
                        }
                    }

                    RadioInterfaceService.RECEIVE_FROMRADIO_ACTION -> {
                        val proto =
                            MeshProtos.FromRadio.parseFrom(
                                intent.getByteArrayExtra(
                                    EXTRA_PAYLOAD
                                )!!
                            )
                        info("Received from radio service: ${proto.toOneLineString()}")
                        when (proto.variantCase.number) {
                            MeshProtos.FromRadio.PACKET_FIELD_NUMBER -> handleReceivedMeshPacket(
                                proto.packet
                            )

                            else -> TODO("Unexpected FromRadio variant")
                        }
                    }

                    else -> TODO("Unexpected radio interface broadcast")
                }
            }
        }
    }

<<<<<<< HEAD
=======
    /// A provisional MyNodeInfo that we will install if all of our node config downloads go okay
    private var newMyNodeInfo: MyNodeInfo? = null

    /// provisional NodeInfos we will install if all goes well
    private val newNodes = mutableListOf<MeshProtos.NodeInfo>()

    /// Used to make sure we never get foold by old BLE packets
    private var configNonce = 1


    private fun handleRadioConfig(radio: MeshProtos.RadioConfig) {
        radioConfig = radio
    }

    /**
     * Convert a protobuf NodeInfo into our model objects and update our node DB
     */
    private fun installNodeInfo(info: MeshProtos.NodeInfo) {
        // Just replace/add any entry
        updateNodeInfo(info.num) {
            if (info.hasUser())
                it.user =
                    MeshUser(
                        info.user.id,
                        info.user.longName,
                        info.user.shortName
                    )

            if (info.hasPosition()) {
                // For the local node, it might not be able to update its times because it doesn't have a valid GPS reading yet
                // so if the info is for _our_ node we always assume time is current
                it.position = Position(info.position)
            }
        }
    }

    private fun handleNodeInfo(info: MeshProtos.NodeInfo) {
        debug("Received nodeinfo num=${info.num}, hasUser=${info.hasUser()}, hasPosition=${info.hasPosition()}")

        logAssert(newNodes.size <= 256) // Sanity check to make sure a device bug can't fill this list forever
        newNodes.add(info)
    }


    /**
     * Update the nodeinfo (called from either new API version or the old one)
     */
    private fun handleMyInfo(myInfo: MeshProtos.MyNodeInfo) {
        setFirmwareUpdateFilename(myInfo)

        val mi = with(myInfo) {
            MyNodeInfo(
                myNodeNum,
                hasGps,
                region,
                hwModel,
                firmwareVersion,
                firmwareUpdateFilename != null,
                SoftwareUpdateService.shouldUpdate(this@MeshService, firmwareVersion),
                currentPacketId.toLong() and 0xffffffffL,
                if (nodeNumBits == 0) 8 else nodeNumBits,
                if (packetIdBits == 0) 8 else packetIdBits,
                if (messageTimeoutMsec == 0) 5 * 60 * 1000 else messageTimeoutMsec, // constants from current device code
                minAppVersion
            )
        }

        newMyNodeInfo = mi

        /// Track types of devices and firmware versions in use
        GeeksvilleApplication.analytics.setUserInfo(
            DataPair("region", mi.region),
            DataPair("firmware", mi.firmwareVersion),
            DataPair("has_gps", mi.hasGPS),
            DataPair("hw_model", mi.model),
            DataPair("dev_error_count", myInfo.errorCount)
        )

        if (myInfo.errorCode != 0) {
            GeeksvilleApplication.analytics.track(
                "dev_error",
                DataPair("code", myInfo.errorCode),
                DataPair("address", myInfo.errorAddress),

                // We also include this info, because it is required to correctly decode address from the map file
                DataPair("firmware", mi.firmwareVersion),
                DataPair("hw_model", mi.model),
                DataPair("region", mi.region)
            )
        }
    }

    private fun handleConfigComplete(configCompleteId: Int) {
        if (configCompleteId == configNonce) {
            // This was our config request
            if (newMyNodeInfo == null || newNodes.isEmpty())
                reportError("Did not receive a valid config")
            else {
                debug("Installing new node DB")
                discardNodeDB()
                myNodeInfo = newMyNodeInfo

                newNodes.forEach(::installNodeInfo)
                newNodes.clear() // Just to save RAM ;-)

                haveNodeDB = true // we now have nodes from real hardware
                processEarlyPackets() // send receive any packets that were queued up

                // broadcast an intent with our new connection state
                broadcastConnection()
                onNodeDBChanged()
                reportConnection()
            }
        } else
            warn("Ignoring stale config complete")
    }

    /**
     * Start the modern (REV2) API configuration flow
     */
    private fun startConfig() {
        configNonce += 1
        newNodes.clear()
        newMyNodeInfo = null

        sendToRadio(ToRadio.newBuilder().apply {
            this.wantConfigId = configNonce
        })
    }

>>>>>>> upstream/master
    /// Send a position (typically from our built in GPS) into the mesh
    private fun sendPosition(
        lat: Double,
        lon: Double,
        alt: Int,
        destNum: Int = NODENUM_BROADCAST,
        wantResponse: Boolean = false
    ) {
        debug("Sending our position to=$destNum lat=$lat, lon=$lon, alt=$alt")

        val position = MeshProtos.Position.newBuilder().also {
            it.latitude = lat
            it.longitude = lon
            it.altitude = alt
            it.time = currentSecond() // Include our current timestamp
        }.build()

        // encapsulate our payload in the proper protobufs and fire it off
        val packet = newMeshPacketTo(destNum)

        packet.payload = MeshProtos.SubPacket.newBuilder().also {
            it.position = position
            it.wantResponse = wantResponse
        }.build()

        // Also update our own map for our nodenum, by handling the packet just like packets from other users
        handleReceivedPosition(myNodeInfo!!.myNodeNum, position)

        // send the packet into the mesh
        sendToRadio(ToRadio.newBuilder().apply {
            this.packet = packet.build()
        })
    }

    /**
     * Send a mesh packet to the radio, if the radio is not currently connected this function will throw NotConnectedException
     */
<<<<<<< HEAD
    private fun sendMeshPacket(packet: MeshPacket) {
        sendToRadio(ToRadio.newBuilder().apply {
            this.packet = packet
=======
    fun setOwner(myId: String?, longName: String, shortName: String) {
        debug("SetOwner $myId : ${longName.anonymize} : $shortName")

        val user = MeshProtos.User.newBuilder().also {
            if (myId != null)  // Only set the id if it was provided
                it.id = myId
            it.longName = longName
            it.shortName = shortName
        }.build()

        // Also update our own map for our nodenum, by handling the packet just like packets from other users
        if (myNodeInfo != null) {
            handleReceivedUser(myNodeInfo!!.myNodeNum, user)
        }

        // set my owner info
        if (RadioInterfaceService.isOldApi!!)
            connectedRadio.writeOwner(user.toByteArray())
        else sendToRadio(ToRadio.newBuilder().apply {
            this.setOwner = user
>>>>>>> upstream/master
        })
    }

<<<<<<< HEAD
    private val binder = object : IMeshService.Stub() {
=======
    /// Do not use directly, instead call generatePacketId()
    private var currentPacketId = 0L

    /**
     * Generate a unique packet ID (if we know enough to do so - otherwise return 0 so the device will do it)
     */
    private fun generatePacketId(): Int {

        myNodeInfo?.let {
            val numPacketIds =
                ((1L shl it.packetIdBits) - 1).toLong() // A mask for only the valid packet ID bits, either 255 or maxint

            if (currentPacketId == 0L) {
                logAssert(it.packetIdBits == 8 || it.packetIdBits == 32) // Only values I'm expecting (though we don't require this)

                val devicePacketId = if (it.currentPacketId == 0L) {
                    // Old devices don't send their current packet ID, in that case just pick something random and it will probably be fine ;-)
                    val random = Random(System.currentTimeMillis())
                    random.nextLong().absoluteValue
                } else
                    it.currentPacketId

                // Not inited - pick a number on the opposite side of what the device is using
                currentPacketId = devicePacketId + numPacketIds / 2
            } else {
                currentPacketId++
            }

            currentPacketId = currentPacketId and 0xffffffff // keep from exceeding 32 bits

            // Use modulus and +1 to ensure we skip 0 on any values we return
            return ((currentPacketId % numPacketIds) + 1L).toInt()
        }

        return 0 // We don't have mynodeinfo yet, so just let the radio eventually assign an ID
    }

    var firmwareUpdateFilename: String? = null

    /***
     * Return the filename we will install on the device
     */
    fun setFirmwareUpdateFilename(info: MeshProtos.MyNodeInfo) {
        firmwareUpdateFilename = try {
            if (info.region != null && info.firmwareVersion != null && info.hwModel != null)
                SoftwareUpdateService.getUpdateFilename(
                    this,
                    info.region,
                    info.firmwareVersion,
                    info.hwModel
                )
            else
                null
        } catch (ex: Exception) {
            errormsg("Unable to update", ex)
            null
        }
    }

    private fun doFirmwareUpdate() {
        // Run in the IO thread
        val filename = firmwareUpdateFilename ?: throw Exception("No update filename")
        val safe =
            RadioInterfaceService.safe ?: throw Exception("Can't update - no bluetooth connected")

        serviceScope.handledLaunch {
            SoftwareUpdateService.doUpdate(this@MeshService, safe, filename)
        }
    }

    /**
     * Remove any sent packets that have been sitting around too long
     *
     * Note: we give each message what the timeout the device code is using, though in the normal
     * case the device will fail after 3 retries much sooner than that (and it will provide a nak to us)
     */
    private fun deleteOldPackets() {
        myNodeInfo?.apply {
            val now = System.currentTimeMillis()
            sentPackets.values.forEach { p ->
                if (p.status == MessageStatus.ENROUTE && p.time + messageTimeoutMsec < now)
                    handleAckNak(false, p.id)
            }
        }
    }

    val binder = object : IMeshService.Stub() {

        override fun setDeviceAddress(deviceAddr: String?) = toRemoteExceptions {
            debug("Passing through device change to radio service: $deviceAddr")
            discardNodeDB()
            radio.service.setDeviceAddress(deviceAddr)
        }

>>>>>>> upstream/master
        // Note: bound methods don't get properly exception caught/logged, so do that with a wrapper
        // per https://blog.classycode.com/dealing-with-exceptions-in-aidl-9ba904c6d63
        override fun subscribeReceiver(packageName: String, receiverName: String) =
            toRemoteExceptions {
                clientPackages[receiverName] = packageName
            }

<<<<<<< HEAD
=======
        override fun getOldMessages(): MutableList<DataPacket> {
            return recentDataPackets
        }

        override fun getUpdateStatus(): Int = SoftwareUpdateService.progress

        override fun startFirmwareUpdate() = toRemoteExceptions {
            doFirmwareUpdate()
        }

        override fun getMyNodeInfo(): MyNodeInfo? = this@MeshService.myNodeInfo

>>>>>>> upstream/master
        override fun getMyId() = toRemoteExceptions { myNodeID }

        override fun setOwner(myId: String?, longName: String, shortName: String) =
            toRemoteExceptions {
                debug("SetOwner $myId : $longName : $shortName")

                val user = MeshProtos.User.newBuilder().also {
                    if (myId != null)  // Only set the id if it was provided
                        it.id = myId
                    it.longName = longName
                    it.shortName = shortName
                }.build()

                // Also update our own map for our nodenum, by handling the packet just like packets from other users
                if (myNodeInfo != null) {
                    handleReceivedUser(myNodeInfo!!.myNodeNum, user)
                }

                // set my owner info
                connectedRadio.writeOwner(user.toByteArray())
            }

        override fun send(
            p: DataPacket
        ) {
            toRemoteExceptions {
                // Init from and id
                myNodeID?.let { myId ->
                    if (p.from == DataPacket.ID_LOCAL)
                        p.from = myId

                    if (p.id == 0)
                        p.id = generatePacketId()
                }
<<<<<<< HEAD
                // If radio is sleeping, queue the packet
                when (connectionState) {
                    ConnectionState.DEVICE_SLEEP ->
                        offlineSentPackets.add(packet)
                    else ->
                        sendMeshPacket(packet)
=======

                info("sendData dest=${p.to}, id=${p.id} <- ${p.bytes!!.size} bytes (connectionState=$connectionState)")

                // Keep a record of datapackets, so GUIs can show proper chat history
                rememberDataPacket(p)

                if (p.id != 0) { // If we have an ID we can wait for an ack or nak
                    deleteOldPackets()
                    sentPackets[p.id] = p
                }

                // If radio is sleeping, queue the packet
                when (connectionState) {
                    ConnectionState.DEVICE_SLEEP -> {
                        p.status = MessageStatus.QUEUED
                        offlineSentPackets.add(p)
                    }
                    else -> {
                        p.status = MessageStatus.ENROUTE

                        // encapsulate our payload in the proper protobufs and fire it off
                        val packet = toMeshPacket(p)
                        sendToRadio(packet)
                    }
>>>>>>> upstream/master
                }

                GeeksvilleApplication.analytics.track(
                    "data_send",
                    DataPair("num_bytes", p.bytes.size),
                    DataPair("type", p.dataType)
                )

<<<<<<< HEAD
                connectionState == ConnectionState.CONNECTED
=======
                GeeksvilleApplication.analytics.track(
                    "num_data_sent",
                    DataPair(1)
                )
>>>>>>> upstream/master
            }
        }

        override fun getRadioConfig(): ByteArray = toRemoteExceptions {
            this@MeshService.radioConfig?.toByteArray()
                ?: throw RadioNotConnectedException()
        }

        override fun setRadioConfig(payload: ByteArray) = toRemoteExceptions {
            // Update our device
            connectedRadio.writeRadioConfig(payload)

            // Update our cached copy
            this@MeshService.radioConfig = MeshProtos.RadioConfig.parseFrom(payload)
        }

        override fun getNodes(): Array<NodeInfo> = toRemoteExceptions {
            val r = nodeDBbyID.values.toTypedArray()
            info("in getOnline, count=${r.size}")
            // return arrayOf("+16508675309")
            r
        }

        override fun connectionState(): String = toRemoteExceptions {
            val r = this@MeshService.connectionState
            info("in connectionState=$r")
            r.toString()
        }
    }
}
