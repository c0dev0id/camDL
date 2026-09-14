package de.codevoid.camdl.wifi

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import de.codevoid.camdl.probe.ProbeLog
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Joins a camera's access point and hands back sockets bound to it.
 *
 * Two things here are easy to get wrong and expensive to debug:
 *
 * The SSID must be **exact**. Android remembers a user's approval per app and access point
 * for an exact match, but an SSID or BSSID *pattern* re-prompts on every single connection.
 *
 * Every socket must be **bound to the returned network**. A camera AP has no internet, so
 * Android leaves the default route on cellular; anything unbound silently leaves by the wrong
 * interface and fails in a way that looks exactly like a camera that is not answering. That
 * is why [WifiLease] hands out pre-bound sockets rather than exposing the raw [Network] and
 * trusting callers to remember - and why binding is done per socket rather than with
 * `bindProcessToNetwork`, which would drag every unrelated request in the process along.
 */
@SuppressLint("MissingPermission") // NEARBY_WIFI_DEVICES is held before this is constructed
class WifiJoiner(
    context: Context,
    private val probe: ProbeLog,
    private val tag: String = "wifi",
) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
        ?: throw IOException("no ConnectivityManager")

    suspend fun join(ssid: String, passphrase: String, timeout: Duration = JOIN_TIMEOUT): WifiLease {
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(passphrase)
            .build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            // The camera AP has no internet. Leaving this capability in place would make the
            // request unsatisfiable and it would simply never come up.
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        val available = CompletableDeferred<Network>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                probe.note(tag, "onAvailable", mapOf("network" to network.toString()))
                available.complete(network)
            }

            override fun onUnavailable() {
                probe.note(tag, "onUnavailable", emptyMap())
                available.completeExceptionally(IOException("the system could not join $ssid"))
            }

            override fun onLost(network: Network) {
                probe.note(tag, "onLost", mapOf("network" to network.toString()))
            }
        }

        probe.note(
            tag,
            "requestNetwork",
            mapOf("ssid" to (probe.redactor.register(ssid, "SSID") ?: ssid)),
        )
        probe.redactor.register(passphrase, "PSK")

        connectivity.requestNetwork(request, callback)
        val network = try {
            withTimeoutOrNull(timeout) { available.await() }
                ?: throw IOException("joining $ssid timed out after $timeout")
        } catch (t: Throwable) {
            connectivity.unregisterNetworkCallback(callback)
            throw t
        }

        return WifiLease(network, probe, tag) {
            connectivity.unregisterNetworkCallback(callback)
        }
    }

    companion object {
        /**
         * The approval dialog is part of this wait the first time round, and the camera's AP
         * takes roughly fifteen seconds to come up after provisioning.
         */
        val JOIN_TIMEOUT = 60.seconds
    }
}

/**
 * A held connection to a camera's access point. Closing it releases the request, after which
 * Android tears the network down.
 */
class WifiLease internal constructor(
    val network: Network,
    private val probe: ProbeLog,
    private val tag: String,
    private val release: () -> Unit,
) : AutoCloseable {

    /** A TCP socket already bound to this network. */
    fun socket(): Socket = Socket().also {
        network.bindSocket(it)
        probe.note(tag, "socket bound", mapOf("network" to network.toString()))
    }

    /** A datagram socket already bound to this network. */
    fun datagramSocket(): DatagramSocket = DatagramSocket().also {
        network.bindSocket(it)
        probe.note(tag, "datagram socket bound", mapOf("network" to network.toString()))
    }

    /**
     * Opens and closes a TCP connection, purely to prove packets reach the camera over this
     * network rather than leaving by some other interface.
     *
     * Port 7001 is the poke the DUML transport needs before UDP works anyway, so this is a
     * real step in the handshake as well as a reachability check.
     */
    fun poke(host: String, port: Int, timeoutMillis: Int = 5_000) {
        socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), timeoutMillis)
            probe.note(
                tag,
                "reachable",
                mapOf("host" to host, "port" to port.toString(), "local" to socket.localAddress.toString()),
            )
        }
    }

    override fun close() {
        release()
        probe.note(tag, "lease released", emptyMap())
    }
}
