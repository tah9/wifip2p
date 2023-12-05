package com.nuist.wifip2p

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.WifiP2pManager.Channel
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.nuist.wifip2p.ui.theme.Wifip2pTheme
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket

var mIntent: Intent? = null

class MainActivity : ComponentActivity() {

    val manager: WifiP2pManager? by lazy(LazyThreadSafetyMode.NONE) {
        getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager?
    }

    var mChannel: WifiP2pManager.Channel? = null
    var receiver: BroadcastReceiver? = null

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Wifip2pTheme {
                // A surface container using the 'background' color from the theme
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MessageList(manager!!, mChannel!!)
                    Column(Modifier.fillMaxSize()) {
                        Spacer(Modifier.weight(1f))
                        connectDevicesList()
                    }
                }
            }
        }
        if (manager == null) {
            Toast.makeText(this, "该设备不支持", Toast.LENGTH_LONG).show()
            return
        }

        mChannel = manager?.initialize(this, mainLooper, WifiP2pManager.ChannelListener {

        })
        manager?.removeGroup(mChannel, null)
        mChannel = manager?.initialize(this, mainLooper, WifiP2pManager.ChannelListener {

        })

        mChannel?.also { channel ->
            receiver = WiFiDirectBroadcastReceiver(channel, manager!!, this)
        }


        // TODO: Consider calling
        //    ActivityCompat#requestPermissions
        // here to request the missing permissions, and then overriding
        //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
        //                                          int[] grantResults)
        // to handle the case where the user grants the permission. See the documentation
        // for ActivityCompat#requestPermissions for more details.
//            return

        manager?.discoverPeers(mChannel, object : WifiP2pManager.ActionListener {

            override fun onSuccess() {
                Log.e(TAG, "onSuccess: discoverPeers")
            }

            override fun onFailure(reasonCode: Int) {
                Log.e(TAG, "onFailure: discoverPeers$reasonCode")
            }
        })

    }

    val intentFilter = IntentFilter().apply {
        addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
    }

    /* register the broadcast receiver with the intent values to be matched */
    override fun onResume() {
        super.onResume()
        receiver?.also { receiver ->
            registerReceiver(receiver, intentFilter)
        }
    }

    /* unregister the broadcast receiver */
    override fun onPause() {
        super.onPause()
        receiver?.also { receiver ->
            unregisterReceiver(receiver)
        }
    }
}

private const val TAG = "debug..."


/**
 * A BroadcastReceiver that notifies of important Wi-Fi p2p events.
 */
var serverSocket: ServerSocket? = null

class WiFiDirectBroadcastReceiver(
    private val channel: Channel,
    private val manager: WifiP2pManager,
    private val activity: MainActivity
) : BroadcastReceiver() {
    private val peers = mutableListOf<WifiP2pDevice>()

    override fun onReceive(context: Context, intent: Intent) {
        mIntent = intent
        var action: String? = intent.action
        when (action) {
            WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                // Check to see if Wi-Fi is enabled and notify appropriate activity
                val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                when (state) {
                    WifiP2pManager.WIFI_P2P_STATE_ENABLED -> {
                        // Wifi P2P is enabled
                        Log.e(TAG, "onReceive: p2p is enabled")
                    }

                    else -> {
                        // Wi-Fi P2P is not enabled
                        Log.e(TAG, "onReceive: p2p is disabled")
                    }
                }
            }

            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                // Call WifiP2pManager.requestPeers() to get a list of current peers
                if (ActivityCompat.checkSelfPermission(
                        activity,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) != PackageManager.PERMISSION_GRANTED || ActivityCompat.checkSelfPermission(
                        activity,
                        Manifest.permission.NEARBY_WIFI_DEVICES
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    Log.d(TAG, "onReceive: PERMISSION_GRANTED")

                }
                manager?.requestPeers(channel) { peerList: WifiP2pDeviceList? ->
                    // Handle peers list
                    var refreshedPeers = peerList!!.deviceList
                    if (refreshedPeers != peers) {
                        peers.clear()
                        peers.addAll(refreshedPeers)
                    }

                    if (peers.isEmpty()) {
                        Log.e(TAG, "No devices found")
//                        return@PeerListListener
                    }
                    updateDevices(peerList.deviceList.toMutableList())
                }
            }

            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                //此处状态检测触发频繁，要避免反复触发，做好标志位
                manager.requestConnectionInfo(
                    channel,
                    object : WifiP2pManager.ConnectionInfoListener {
                        override fun onConnectionInfoAvailable(info: WifiP2pInfo) {
                            var port: Int = 20112

                            //组未搭建完毕，屏蔽
                            if (info.groupFormed.not()) return

                            Toast.makeText(context, "${info.isGroupOwner}", Toast.LENGTH_SHORT)
                                .show()

                            if (info.isGroupOwner) {
                                // 该设备是群组的所有者

                                //避免重复创建
                                if (serverSocket!=null)return

                                Thread {
                                    serverSocket = ServerSocket(port)
                                    Thread {
                                        var accept = serverSocket?.accept()
                                        var dos = DataOutputStream(accept?.getOutputStream())
                                        dos.writeUTF(
                                            "来自点对点"
                                        )
                                        dos.flush()
                                    }.start()
                                }.start()
                            } else {
                                Log.d(TAG, "onConnectionInfoAvailable: ${info.groupOwnerAddress}")
                                // 该设备是群组的成员，但不是所有者
                                object : Thread() {
                                    override fun run() {
                                        var socket =
                                            Socket(info.groupOwnerAddress, port)
                                        object : Thread() {
                                            override fun run() {
                                                var inputStream =
                                                    DataInputStream(socket.getInputStream())
                                                var txt = inputStream.readUTF()
                                                Log.d(TAG, "run: $txt")
                                            }
                                        }.start()
                                    }
                                }.start()
                            }
                        }
                    })
            }

            WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                // Respond to this device's wifi state changing
            }
        }
    }
}

val wifiDevices = mutableStateListOf<WifiP2pDevice>()

@Composable
fun MessageList(
    manager: WifiP2pManager,
    channel: WifiP2pManager.Channel
) {
    val context = LocalContext.current

    LazyColumn {
        items(wifiDevices) { item ->
            Text(
                text = item.deviceName,
                modifier = Modifier
                    .padding(top = 10.dp, bottom = 10.dp)
                    .clickable {
                        attemptToConnect(item, context, manager, channel)
                    }
            )
        }
    }
}

fun updateDevices(newDevices: List<WifiP2pDevice>) {
    wifiDevices.clear()
    wifiDevices.addAll(newDevices)
}

val connectDevices = mutableStateListOf<WifiP2pDevice>()

@Composable
fun connectDevicesList(

) {
    LazyColumn {

        items(connectDevices) { item ->
            Text(
                text = item.deviceName,
                color = Color.Green,
                modifier = Modifier
                    .padding(top = 10.dp, bottom = 10.dp)
            )
        }
    }
}

@SuppressLint("MissingPermission")
fun attemptToConnect(
    device: WifiP2pDevice,
    context: Context,
    manager: WifiP2pManager,
    channel: WifiP2pManager.Channel
) {
    val config = WifiP2pConfig()
    config.deviceAddress = device.deviceAddress
    manager?.connect(
        channel,
        config,
        object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                //success logic
                Log.e(TAG, "onSuccess: logic${device.deviceName}")
                connectDevices.add(device)


            }

            override fun onFailure(reason: Int) {
                //failure logic
                Log.e(TAG, "onFailure: logic")
            }
        }
    )
}


