package io.switchlite.adapter.common.webui

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Helps discover the LAN IP(s) to advertise for the WebUI panel.
 *
 * The panel binds 0.0.0.0, so other devices (phone / another PC) reach it
 * through the machine's LAN IP. We scan the local network interfaces and
 * prefer a private IPv4 on an up, non-loopback, non-virtual interface.
 *
 * This is a pure local enumeration — no external "what is my IP" service is
 * contacted, no auth / server verification happens. Nothing leaks to the
 * internet, and the advertised address simply follows whatever network the
 * machine is currently connected to.
 */
object LanHelper {

    /**
     * All usable LAN IPv4 addresses, ordered best-first.
     *
     * - Prefers site-local addresses (192.168.x / 10.x / 172.16-31.x).
     * - Falls back to any other non-loopback IPv4 when no site-local one is up.
     * - Re-scans on every call, so the result updates automatically when the
     *   machine changes network (Wi-Fi, ethernet, VPN, hotspot, ...).
     */
    fun lanAddresses(): List<InetAddress> {
        val siteLocal = mutableListOf<InetAddress>()
        val fallback = mutableListOf<InetAddress>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val netIf = interfaces.nextElement()
                if (!netIf.isUp || netIf.isLoopback || netIf.isVirtual) continue
                val addresses = netIf.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr !is Inet4Address || addr.isLoopbackAddress) continue
                    if (addr.isSiteLocalAddress) siteLocal.add(addr) else fallback.add(addr)
                }
            }
        } catch (_: Exception) {
            // ignore — return whatever was collected (possibly empty)
        }
        return (siteLocal + fallback).distinctBy { it.hostAddress }
    }

    /** Primary LAN IPv4 to advertise, or null if none is up (offline-only). */
    fun lanAddress(): InetAddress? = lanAddresses().firstOrNull()

    /**
     * Human-friendly access URL(s) for the panel.
     *
     * Fully dynamic: every LAN IPv4 gets its own URL. Only when no LAN address
     * is available at all do we fall back to the loopback address so the panel
     * is still reachable from the machine itself.
     */
    fun lanUrls(port: Int): List<String> {
        val urls = lanAddresses().map { "http://${it.hostAddress}:$port" }
        return if (urls.isNotEmpty()) urls else listOf("http://127.0.0.1:$port")
    }
}
