package ai.maynards.mesh.engine

import java.net.Inet4Address
import java.net.NetworkInterface

/** A local IPv4 address and the kind of link it sits on. */
data class LinkAddr(val ip: String, val iface: String) {
    val kind: String get() = when {
        // Tailscale on Android exposes its VPN tunnel as a tun* interface. Do not classify by the
        // 100.64/10 address range: mobile carriers also use that CGNAT range (e.g. rmnet_data*).
        iface.startsWith("tun") || iface.startsWith("tailscale") -> "tailscale"
        // wlan0 is the Wi-Fi client; phones name their hotspot ap0, swlan0, or a second wlan (wlan1, wlan2)
        iface.startsWith("ap") || iface.startsWith("swlan") || (iface.startsWith("wlan") && iface != "wlan0") -> "hotspot"
        iface.startsWith("rndis") || iface.startsWith("usb") || iface.startsWith("ncm") -> "usb"
        iface.startsWith("wlan") -> "wifi"
        else -> "other"
    }
}

object Net {
    /**
     * Is there a working way out to the internet right now? The demo turns it off on purpose, so the
     * screen should say so rather than leave a judge guessing. VALIDATED means Android actually
     * reached the internet on that network, not merely that an interface is up.
     */
    fun online(ctx: android.content.Context): Boolean = runCatching {
        val cm = ctx.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(false)

    /** Prefer the encrypted overlay for remote peers, then local hotspot/USB/Wi-Fi addresses. */
    fun addresses(): List<LinkAddr> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nif -> nif.inetAddresses.toList().filterIsInstance<Inet4Address>().map { LinkAddr(it.hostAddress!!, nif.name) } }
            .filter { it.kind != "other" }
            .sortedBy { listOf("tailscale", "hotspot", "usb", "wifi").indexOf(it.kind) }
    }.getOrDefault(emptyList())

    fun best(): LinkAddr? = addresses().firstOrNull()
}
