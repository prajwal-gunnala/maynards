package ai.maynards.origami.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OrigamiAIBridgeTest {
    private val verified = OrganizerSpec(widthMm = 260, depthMm = 170, heightMm = 140, materialThicknessMm = 6)

    private fun response(arguments: String) = """
        {"choices":[{"message":{"tool_calls":[{"function":{
          "name":"generate_organizer","arguments":${org.json.JSONObject.quote(arguments)}
        }}]}}]}
    """.trimIndent()

    @Test fun acceptedToolCallChangesOnlySupportedSettings() {
        val spec = OrigamiAIBridge.parseToolArguments(
            response("{\"compartments\":3,\"clearance_mm\":0.35}"), verified)
        assertEquals(260, spec.widthMm)
        assertEquals(170, spec.depthMm)
        assertEquals(140, spec.heightMm)
        assertEquals(6, spec.materialThicknessMm)
        assertEquals(3, spec.compartments)
        assertEquals(0.35, spec.clearanceMm, 0.0001)
        OrganizerGenerator.generate(spec)
    }

    @Test fun unsupportedFieldsAreRejected() {
        assertThrows(IllegalStateException::class.java) {
            OrigamiAIBridge.parseToolArguments(
                response("{\"compartments\":3,\"clearance_mm\":0.3,\"width_mm\":400}"), verified)
        }
    }

    @Test fun invalidEngineeringValuesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            OrigamiAIBridge.parseToolArguments(
                response("{\"compartments\":8,\"clearance_mm\":0.3}"), verified)
        }
    }

    @Test fun referencePhotoUsesOpenAiMultimodalContentParts() {
        val parts = OrigamiAIBridge().buildUserContent(
            "fit my desk", verified, "data:image/jpeg;base64,AA==") as org.json.JSONArray
        assertTrue(parts.getJSONObject(0).getString("text").contains("attached photo"))
        assertEquals("image_url", parts.getJSONObject(1).getString("type"))
        assertEquals("data:image/jpeg;base64,AA==", parts.getJSONObject(1)
            .getJSONObject("image_url").getString("url"))
    }
}
