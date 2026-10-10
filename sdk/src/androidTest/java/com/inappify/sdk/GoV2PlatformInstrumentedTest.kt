package com.inappify.sdk

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.inappify.sdk.internal.storage.EncryptedSessionStateStore
import com.inappify.sdk.internal.storage.PersistedSession
import java.io.File
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GoV2PlatformInstrumentedTest {
    @Test fun goStorageIsEncryptedAndIndependentOfV1() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val legacy = EncryptedSessionStateStore.create(context)
        val go = EncryptedSessionStateStore.createGoV2(context)
        fun state(token: String) = PersistedSession(token, "customer", 4, 12, null, "test-app",
            "{\"sessionToken\":\"$token\"}", null, null)
        try {
            assertTrue(legacy.save(state("legacy-test-token")))
            assertTrue(go.save(state("go-test-token")))
            assertEquals("legacy-test-token", legacy.load()?.token)
            assertEquals("go-test-token", go.load()?.token)
            val encrypted = File(context.noBackupFilesDir, "inappify_go_session_v2.bin").readBytes()
            assertFalse(encrypted.toString(Charsets.ISO_8859_1).contains("go-test-token"))
            assertTrue(go.clear())
            assertEquals("legacy-test-token", legacy.load()?.token)
        } finally { legacy.clear(); go.clear() }
    }

    @Test fun packageFallbackIsScrollableRtlAndResolvesExactPackage() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val item = InappifyPackage("monthly", product = InappifyStoreProduct("premium", "اشتراک ماهانه",
                listOf(InappifyPrice(currency = "IRR", price = 1500000)), trialDays = 7))
            var selected: InappifyPackage? = null
            val view = InappifyPaywall.createPackageView(instrumentation.targetContext,
                InappifyOffering("default", packages = listOf(item)), locale = Locale("fa"), darkMode = true,
                onPurchase = { selected = it }) as ViewGroup
            view.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 320, 480)
            val content = view.getChildAt(0) as ViewGroup
            assertEquals(View.LAYOUT_DIRECTION_RTL, content.layoutDirection)
            val button = (0 until content.childCount).map { content.getChildAt(it) }.filterIsInstance<Button>().single()
            assertTrue(button.contentDescription.isNotBlank())
            button.performClick()
            assertSame(item, selected)
        }
    }
}
