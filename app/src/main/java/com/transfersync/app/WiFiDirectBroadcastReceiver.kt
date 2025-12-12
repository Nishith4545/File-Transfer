package com.transfersync.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pManager
import androidx.annotation.RequiresPermission

class WiFiDirectBroadcastReceiver() : BroadcastReceiver() {

    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var activity: MainActivity? = null

    fun initialize(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        activity: MainActivity
    ) {
        this.manager = manager
        this.channel = channel
        this.activity = activity
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES])
    override fun onReceive(context: Context, intent: Intent) {
        val manager = manager ?: return
        val channel = channel ?: return
        val activity = activity ?: return

        when (intent.action) {
            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                manager.requestPeers(channel) { peers ->
                    activity.updatePeerList(peers.deviceList.toList())
                }
            }

            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                val networkInfo =
                    intent.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                if (networkInfo?.isConnected == true) {
                    manager.requestConnectionInfo(channel) { info ->
                        activity.onConnectionInfoAvailable(info)
                    }
                } else {
                    activity.appendLog("Disconnected from Wi-Fi Direct")
                }
            }
        }
    }
}

