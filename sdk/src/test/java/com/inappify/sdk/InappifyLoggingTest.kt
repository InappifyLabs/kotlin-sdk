package com.inappify.sdk

import com.inappify.sdk.internal.InappifyLogDispatcher
import com.inappify.sdk.internal.InappifyLogger
import com.inappify.sdk.internal.network.*
import com.inappify.sdk.internal.platform.*
import com.inappify.sdk.internal.v2.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class InappifyLoggingTest {
    private class RecordingHandler(val accept: (InappifyLogLevel, String) -> Unit) : InappifyLogHandler {
        override fun v(tag: String, msg: String) = accept(InappifyLogLevel.VERBOSE, msg)
        override fun d(tag: String, msg: String) = accept(InappifyLogLevel.DEBUG, msg)
        override fun i(tag: String, msg: String) = accept(InappifyLogLevel.INFO, msg)
        override fun w(tag: String, msg: String) = accept(InappifyLogLevel.WARN, msg)
        override fun e(tag: String, msg: String, throwable: Throwable?) = accept(InappifyLogLevel.ERROR, msg)
    }

    @Test fun minimumSeverityFiltersBeforeBuildingMessages() {
        val seen = mutableListOf<InappifyLogLevel>()
        val logger = InappifyLogDispatcher(InappifyLogLevel.INFO, RecordingHandler { level, _ -> seen += level })
        logger.log(InappifyLogLevel.DEBUG) { error("Filtered messages must not be evaluated") }
        for (level in InappifyLogLevel.values()) logger.log(level) { "Safe diagnostic." }
        assertEquals(listOf(InappifyLogLevel.INFO, InappifyLogLevel.WARN, InappifyLogLevel.ERROR), seen)
        logger.level = InappifyLogLevel.VERBOSE
        logger.log(InappifyLogLevel.VERBOSE) { "Detail." }
        assertEquals(InappifyLogLevel.VERBOSE, seen.last())
    }

    @Test fun throwingAndRecursiveHandlersCannotBreakSdkWork() {
        val logger = InappifyLogDispatcher(InappifyLogLevel.DEBUG, RecordingHandler { _, _ -> error("Host failure") })
        logger.log(InappifyLogLevel.ERROR) { "Failure diagnostic." }
        var calls = 0
        logger.handler = RecordingHandler { _, _ ->
            calls++
            logger.log(InappifyLogLevel.ERROR) { "Recursive host callback." }
        }
        logger.log(InappifyLogLevel.ERROR) { "Outer diagnostic." }
        assertEquals(1, calls)
    }

    @Test fun resourceIdentifiersAndUnknownPathsAreNotPrinted() {
        assertEquals("consumable-deliveries/:id/delivered", InappifyLogger.endpoint("consumable-deliveries/123456/delivered"))
        assertEquals("store/verifications/:id/status", InappifyLogger.endpoint("store/verifications/private-id/status"))
        assertEquals("[redacted]", InappifyLogger.endpoint("https://example.test/?token=secret"))
    }

    @Test fun handlerBeforeConfigureReceivesLifecycleButNeverCredentialsOrCustomerData() = runBlocking {
        val oldLevel = InappifyClient.logLevel
        val oldHandler = InappifyClient.logHandler
        val seen = mutableListOf<String>()
        val signing = SigningFixture()
        val transport = V2Transport().apply {
            handler = { response(signing.envelope()) }
        }
        try {
            InappifyClient.logLevel = InappifyLogLevel.VERBOSE
            InappifyClient.logHandler = RecordingHandler { _, message -> seen += message }
            GoV2Client(signing.config, GoApi(transport, { signing.now }, {}), MemoryV2Store(),
                AppMetadataProvider { AppMetadata("com.example.mobile", "2.4.0", 20400) },
                { signing.now }, Dispatchers.Unconfined, backgroundRecovery = false).use { client ->
                assertTrue(client.configure(InappifyOptions("sensitive-public-key", signing.subject)) is InappifyResult.Success)
                assertTrue(client.getCustomerInfo() is InappifyResult.Success)
            }
            assertTrue(seen.any { it.contains("Configuring SDK") })
            assertTrue(seen.any { it.contains("cache available") })
            for (secret in listOf("sensitive-public-key", signing.subject, "go-session-token", "EdDSA", "customer_info"))
                assertFalse(seen.joinToString().contains(secret))
        } finally {
            InappifyClient.logLevel = oldLevel
            InappifyClient.logHandler = oldHandler
        }
    }
}
