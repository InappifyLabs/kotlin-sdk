package com.inappify.sdk.internal.v2

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class MyketPurchasePayloadTest {
    private fun payload() = JsonObject().apply {
        addProperty("offeringIdentifier", "default")
        addProperty("productIdentifier", "monthly")
        addProperty("nativePackageIdentifier", "ina_monthly")
        addProperty("attemptId", "poolex-" + "0".repeat(32))
        addProperty("productType", "SUBSCRIPTION")
        addProperty("recoveryBinding", "a".repeat(64))
        addProperty("purchaseBinding", "v1:" + "b".repeat(64))
    }

    @Test fun poolexPayloadFitsWhileBothBindingsAndRecoveryContextRoundTrip() {
        val original = payload()
        assertTrue(original.toString().toByteArray().size > 300)
        val wire = MyketPurchasePayload.encode(original)
        assertEquals(231, wire.toByteArray().size)
        assertEquals(original, MyketPurchasePayload.decode(wire, "monthly"))
        assertEquals(original.get("purchaseBinding"), JsonParser.parseString(wire).asJsonObject.get("purchaseBinding"))
        for (type in listOf("CONSUMABLE", "NON_CONSUMABLE", "SUBSCRIPTION")) {
            original.addProperty("productType", type)
            assertEquals(original, MyketPurchasePayload.decode(MyketPurchasePayload.encode(original), "monthly"))
        }
    }

    @Test fun previousReceiptsRemainReadableWithoutRewritingTheirSignedEvidence() {
        val original = payload()
        assertEquals(original, MyketPurchasePayload.decode(original.toString(), "monthly"))
        original.remove("productType")
        assertEquals(original, MyketPurchasePayload.decode(MyketPurchasePayload.encode(original), "monthly"))
    }

    @Test fun malformedCompactPayloadCannotFallBackToUnboundLegacyRecovery() {
        for (mutate in listOf<(JsonObject) -> Unit>(
            { it.addProperty("v", 2) }, { it.remove("purchaseBinding") },
            { it.addProperty("purchaseBinding", "wrong") }, { it.addProperty("r", "wrong") },
            { it.addProperty("t", "unknown") }, { it.addProperty("productIdentifier", "other") },
        )) {
            val malformed = JsonParser.parseString(MyketPurchasePayload.encode(payload())).asJsonObject
            mutate(malformed)
            assertThrows(IllegalArgumentException::class.java) { MyketPurchasePayload.decode(malformed.toString(), "monthly") }
        }
    }

    @Test fun oversizeContextsAreRejectedBeforeLaunchingStoreUi() {
        val long = payload().apply { addProperty("offeringIdentifier", "a".repeat(128)) }
        assertThrows(IllegalArgumentException::class.java) { MyketPurchasePayload.encode(long) }
        val unicode = payload().apply { addProperty("offeringIdentifier", "آ".repeat(20)) }
        assertThrows(IllegalArgumentException::class.java) { MyketPurchasePayload.encode(unicode) }
    }
}
