package com.inappify.sdk.internal.v2

import org.junit.Assert.*
import org.junit.Test

class PaymentReturnLinkTest {
    private val marker = "inappify_return=payment_v1&inappify_app_id=12&inappify_return_id=payment-123"

    @Test fun acceptsCustomHttpsAndOpaqueUrisWithoutDependingOnTheDevelopersRoute() {
        for (url in listOf("myapp://anything?$marker", "myapp:///another/path?existing=1&$marker#done",
            "https://example.com/app?x=1&$marker#/home?other=1", "myapp:payment?$marker"))
            assertEquals(PaymentReturnLink(12, "payment-123"), PaymentReturnLink.parse(url))
    }
    @Test fun ordinaryOrIncompleteLinksNeverTriggerPaymentRecovery() {
        for (url in listOf("myapp://home", "myapp://home?status=100&transactionId=payment-123",
            "myapp://home?inappify_return=payment_v1", "myapp://home?$marker".replace("payment_v1", "payment_v2"),
            "myapp://home#$marker")) assertNull(url, PaymentReturnLink.parse(url))
    }
    @Test fun rejectsDuplicateMarkersAndMalformedIdentifiersInsteadOfGuessing() {
        for (suffix in listOf("&inappify_return=payment_v1", "&%69nappify_app_id=12", "&inappify_return_id=other"))
            assertNull(PaymentReturnLink.parse("myapp://home?$marker$suffix"))
        for (app in listOf("0", "-1", "01", "999999999999999999999", "12x"))
            assertNull(PaymentReturnLink.parse("myapp://home?${marker.replace("app_id=12", "app_id=$app")}"))
        for (id in listOf("", "contains%20space", "abc%0A", "x".repeat(129)))
            assertNull(PaymentReturnLink.parse("myapp://home?${marker.replace("payment-123", id)}"))
    }
    @Test fun rejectsMalformedAndExecutableUrls() {
        for (url in listOf("javascript:alert(1)?$marker", "data:text/plain,foo?$marker", "file:///tmp?$marker",
            "myapp://home?bad=%GG&$marker", "no-scheme?$marker", "myapp://home?${marker}\n"))
            assertNull(url, PaymentReturnLink.parse(url))
    }
    @Test fun paymentStatusIsNeverPartOfTheTrustDecision() {
        for (status in listOf("100", "101", "102", "104", "forged-success"))
            assertEquals(PaymentReturnLink(12, "payment-123"), PaymentReturnLink.parse("myapp://payment?$marker&status=$status"))
    }
}
