package ai.maynards.mesh.brain

import org.junit.Assert.assertEquals
import org.junit.Test

class RunnerTest {
    @Test fun usesLaptopPeerForRelayedHelperRpc() {
        assertEquals("10.217.74.216:50052", rpcEndpoint("10.155.241.73:50052", "10.217.74.216"))
    }

    @Test fun directHelperConnectionKeepsItsAddress() {
        assertEquals("192.168.112.146:50052", rpcEndpoint("192.168.112.146:50052", null))
    }
}
