package com.inappify.sdk.internal.v2

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Test

class StorePurchaseBindingTest {
    @Test fun matchesLaravelUtf8ContractVector() {
        val customer = MessageDigest.getInstance("SHA-256").digest("کاربر:۱۲".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        assertEquals("v1:5630ece9a6865664a449c656a1f0ad1fab6eb3fc9c1c21dd94f7e1e8cf66cfcd",
            goPurchaseBinding(12, "com.example.mobile", customer, "daily:اشتراک", "offer:اصلی", "ina_daily"))
    }
}
