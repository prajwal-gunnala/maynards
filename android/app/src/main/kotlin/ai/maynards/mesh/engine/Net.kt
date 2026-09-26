package ai.maynards.mesh.engine

import java.net.Inet4Address
import java.net.NetworkInterface

/** A local IPv4 address and the kind of link it sits on. */
data class LinkAddr(val ip: String, val iface: String) {
    val kind: String get() = when {
        // wlan0 is the Wi-Fi client; phones name their hotspot ap0, swlan0, or a second wlan (wlan1, wlan2)
        iface.startsWith("ap") || iface.startsWith("swlan") || (iface.startsWith("wlan") && iface != "wlan0") -> "hotspot"
        iface.startsWith("rndis") || iface.startsWith("usb") || iface.startsWith("ncm") -> "usb"
        iface.startsWith("wlan") -> "wifi"
        else -> "other"
    }
}

object Net {
    /** Private-link addresses first: our own hotspot, then USB, then Wi-Fi. Mobile data is skipped. */
    fun addresses(): List<LinkAddr> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nif -> nif.inetAddresses.toList().filterIsInstance<Inet4Address>().map { LinkAddr(it.hostAddress!!, nif.name) } }
            .filter { it.kind != "other" }
            .sortedBy { listOf("hotspot", "usb", "wifi").indexOf(it.kind) }
    }.getOrDefault(emptyList())

    fun best(): LinkAddr? = addresses().firstOrNull()
}
