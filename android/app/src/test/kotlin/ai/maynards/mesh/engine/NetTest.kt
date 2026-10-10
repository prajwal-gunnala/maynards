package ai.maynards.mesh.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class NetTest {
    @Test fun tailscaleTunnelIsPreferredAndCellularCgnatIsNotMistakenForTailscale() {
        val links = listOf(
            LinkAddr("192.168.1.20", "wlan0"),
            LinkAddr("100.69.64.67", "rmnet_data3"),
            LinkAddr("100.80.0.12", "tun0"),
        )

        assertEquals("tailscale", links.first { it.iface == "tun0" }.kind)
        assertEquals("other", links.first { it.iface == "rmnet_data3" }.kind)
        assertEquals("wifi", links.first { it.iface == "wlan0" }.kind)
    }
}
