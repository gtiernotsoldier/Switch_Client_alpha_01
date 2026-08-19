package io.switchlite.adapter.common.webui

import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Helps discover the LAN IP(s) to advertise for the WebUI panel.
 *
 * When the panel binds 0.0.0.0, other devices (phone / another PC) reach it
 * via the machine's LAN IP. We prefer a private IPv4 on an up, non-loopback,
 * non-virtual interface — the address a user should type into the browser.
 */
object LanHelper {

    /**
     * Best LAN IPv4 to advertise, or null if none is up (e.g. offline-only).
     * Prefers 192.168.x / 10.x / 172.16-31.x links that are up.
     */
    fun lanAddress(): InetAddress? {
        var fallback: InetAddress? = null
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val netIf = interfaces.nextElement()
                if (!netIf.isUp || netIf.isLoopback || netIf.isVirtual) continue
                val addresses = netIf.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is java.net.Inet4Address && addr.isSiteLocalAddress) {
                        return addr
                    }
                    if (addr is java.net.Inet4Address && !addr.isLoopbackAddress && fallback == null) {
                        fallback = addr
                    }
                }
            }
        } catch (e: Exception) {
            // ignore — return fallback or null
        }
        return fallback
    }

    /** Human-friendly LAN URL(s) for the panel on this machine. */
    fun lanUrls(port: Int): List<String> {
        val urls = mutableListOf("http://127.0.0.1:$port")
        lanAddress()?.let { urls.add("http://${it.hostAddress}:$port") }
        return urls
    }
}
