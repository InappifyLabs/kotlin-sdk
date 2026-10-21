package com.inappify.sdk

import com.google.gson.JsonParser
import com.inappify.sdk.internal.domain.InappifyDomainJsonCodec
import org.junit.Assert.*
import org.junit.Test

class RetiredUpdateMetadataTest {
    @Test fun publicStateNoLongerExposesRetiredUpdateProperties() {
        for (type in listOf(InappifySnapshot::class.java, InappifyOfferings::class.java,
            InappifyV2Client::class.java)) {
            assertFalse(type.methods.any { it.name == "getForceVersion" || it.name == "getHasForceUpdate" })
        }
        assertFalse(InappifySnapshot.initial("test-sdk").toString().contains("forceVersion"))
    }

    @Test fun obsoleteResponseFieldsAreIgnoredForBothOfferingFormatsAndNeverReencoded() {
        for (targeting in listOf("", ",\"currentOffering\":null,\"placements\":{}")) {
            val baseline = InappifyDomainJsonCodec.parseOfferings("""{"offerings":[]$targeting}""")
            for (value in listOf("77", "null", "true", "{}", "\"retired\"")) {
                val decoded = InappifyDomainJsonCodec.parseOfferings("""{"offerings":[],
                    "forceVersion":$value,"force_version":$value,"hasForceUpdate":true$targeting}""")
                assertEquals(baseline, decoded)
                val encoded = JsonParser.parseString(InappifyDomainJsonCodec.encodeOfferings(decoded)).asJsonObject
                assertFalse(encoded.has("forceVersion"))
                assertFalse(encoded.has("force_version"))
                assertFalse(encoded.has("hasForceUpdate"))
            }
        }
    }

    @Test fun constructorsNoLongerAcceptRetiredUpdateMetadata() {
        assertTrue(InappifyOfferings::class.java.constructors.none {
            java.lang.Long::class.java in it.parameterTypes
        })
        assertTrue(InappifySnapshot::class.java.constructors.none { constructor ->
            constructor.parameterTypes.count { it == java.lang.Long::class.java } > 1
        })
    }
}
