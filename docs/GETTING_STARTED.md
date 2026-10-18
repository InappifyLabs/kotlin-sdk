# Getting started

This guide uses `com.github.InappifyLabs:kotlin-sdk:v0.1.1` and the public
Kotlin package `com.inappify.sdk`. Follow [installation](../README.md#install)
first. All SDK operations below are suspend functions unless stated otherwise.

## Prepare the application

Register the Android package and its purchase route in Inappify. Configure the
same products, subscriptions and signing/package identity in the selected store.
The offering's product identifier must match the store SKU; its package identifier
is an Inappify catalog identifier, not the Android application ID.

| Route | Configure market | Store setup |
| --- | --- | --- |
| Cafe Bazaar | `InappifyMarket.BAZAAR` | Bazaar app public RSA key, installed Bazaar, signed-in store account; in-app products and subscriptions |
| Myket | `InappifyMarket.MYKET` | Myket app public RSA key, installed Myket, signed-in store account; consumable and non-consumable in-app products |
| Direct | `InappifyMarket.NONE` | Inappify Direct Android route and permitted checkout provider |

Myket subscriptions are unsupported. The SDK includes Poolakey for Bazaar and
an isolated Myket billing implementation. Their merged manifests supply store
permissions, package visibility and Myket components. Inspect the final merged
manifest for your app. You do not add another billing helper or relay
`onActivityResult` into this SDK.

New store purchases need the current foreground Activity. For both Bazaar and
Myket it must
implement AndroidX `ActivityResultRegistryOwner` and `LifecycleOwner`; an
AndroidX `ComponentActivity` or `FragmentActivity` provides these contracts.
Do not keep a destroyed Activity in application scope. The SDK obtains
application metadata from Android and uses the server's verified purchase route.

## Own the session and fulfillment

The following complete class retains one client, registers a delivery handler
before Configure and closes its registrations with its owner. The application
must implement `DurableInventory.grantOnce`: atomically grant the catalog-defined
amount and record the unique delivery key for the correct signed-in customer.
Return true only after that durable transaction commits, including when the same
delivery was already granted. This interface is host code, not an SDK API.

```kotlin
import android.content.Context
import com.inappify.sdk.*

interface DurableInventory {
    suspend fun grantOnce(delivery: InappifyConsumableDelivery): Boolean
}

class BillingSession(
    context: Context,
    inventory: DurableInventory,
    onSnapshot: (InappifySnapshot) -> Unit,
) : AutoCloseable {
    val client: InappifyV2Client = InappifyClient.create(context)

    private val deliveries = client.setConsumableDeliveryHandler { delivery ->
        if (inventory.grantOnce(delivery)) {
            InappifyDeliveryResult.DELIVERED
        } else {
            InappifyDeliveryResult.RETRY_LATER
        }
    }
    private val events = client.addEventListener { event ->
        onSnapshot(event.snapshot)
    }

    suspend fun start(
        appPublicKey: String,
        currentCustomerId: String?,
        market: InappifyMarket,
        marketPublicRsaKey: String?,
        recoverPayloadlessSubscriptions: Boolean = true,
    ): InappifyResult<Unit> = client.configure(
        InappifyOptions(
            apiKey = appPublicKey,
            appUserIdentifier = currentCustomerId,
            market = market,
            marketKey = marketPublicRsaKey,
            enableSubscriptionRecoveryWithoutPayload =
                recoverPayloadlessSubscriptions,
        ),
    )

    override fun close() {
        events.close()
        deliveries.close()
        client.close()
    }
}
```

The delivery callback runs away from the main thread. The production client's
state events dispatch on the Android main thread. Keep both callbacks short;
hand durable writes to appropriate storage. Scope inventory records to the app
and customer, and never let asynchronous work grant a delivery to a newly
selected account.

Call `start` from a host-managed coroutine and check its result before allowing a
purchase. A foreground Activity's coroutine can invoke SDK suspend functions;
network and billing internals manage their own dispatch. Include
`kotlinx-coroutines-android` so the Android main dispatcher is available.

## Identify the customer

Supply your existing authenticated customer ID in `appUserIdentifier` when your
host is signed in. Valid custom IDs contain 1–100 Unicode code points, no control
characters or edge whitespace, and no reserved anonymous prefix. Numeric IDs are
accepted as strings.

When the identifier is omitted, Configure can reuse the customer's existing
verified session; without one it creates and durably saves an anonymous identity.
Omitting the identifier does not mean “log out.” Do not generate a new ID on every
launch. Do not manually manufacture an SDK anonymous ID.

To change accounts after Configure:

```kotlin
import com.inappify.sdk.*

suspend fun selectCustomer(
    client: InappifyV2Client,
    customerId: String,
): InappifyResult<Unit> = client.login(
    InappifyLoginRequest(
        appUserIdentifier = customerId,
    ),
)
```

Login requires only the customer identifier and uses the current session Bearer.
The app public key remains in Configure for session bootstrap and renewal. Await
login before querying or purchasing for the new customer. `logout()` returns to
an anonymous session. Uncertain identity
transitions remain durable; call `recover()` to reconcile them. While logout is
pending, `isLogoutPending` exposes the barrier and predecessor customer data is
hidden. `close()` releases resources and does not log out or delete paid work.

## Load targeted offerings

Configure success does not imply that the background Offerings refresh has
already finished. Fetch or synchronize offerings before displaying a paywall.
Queue custom attributes before selecting the offering:

```kotlin
import com.inappify.sdk.*

suspend fun offeringForPaywall(
    client: InappifyV2Client,
    placement: String,
): InappifyResult<InappifyOffering?> {
    when (val queued = client.queueAttributes(mapOf("family" to "true"))) {
        is InappifyResult.Failure -> return queued
        is InappifyResult.Success -> Unit
    }
    when (val synced = client.syncAttributesAndOfferingsIfNeeded()) {
        is InappifyResult.Failure -> return synced
        is InappifyResult.Success -> Unit
    }
    return client.getCurrentOffering(placementIdentifier = placement)
}
```

Fresh offerings requests flush the durable attribute queue first. The server
performs targeting using the current session, queued attributes, country,
appVersion and SDK version `0.1.1`. Configure/snapshots use this exact
version without the tag prefix; the client does not
normalize it locally for selection. SDK version is separate from your app
version. Current selection uses a matching placement offering, then the server's
`currentOffering`, then a catalog default. A success can contain null.

`syncAttributesAndOfferingsIfNeeded()` allows five admitted calls per rolling
60 seconds per client. Excess calls return current-session cached offerings or a
rate-limit failure if no cache exists; they do not upload attributes or fetch
fresh data. Do not use it as an unbounded polling loop. Use
`refreshOfferings()` or `offerings(NETWORK_ONLY)` when your workflow specifically
requires a new request, handling service limits normally.

For changed country or app version, await `setTargetingContext`, then fetch fresh
offerings. Country is normalized to two uppercase letters. Context changes
invalidate catalog cache. The optional evaluation context passed to
`getCurrentOffering` does not override server targeting on the production client.

A package contains `identifier` and `product.identifier`; retain both from the
selected offering. Product `prices` provide catalog price data. The API does not
expose a product-type field in that catalog model: use your app's configured
product classification when creating the purchase request.

## Start a store purchase

Use the selected offering/package and a stable attempt ID. The following helper
handles nullable catalog fields explicitly and calls the Activity overload:

```kotlin
import android.app.Activity
import com.inappify.sdk.*

suspend fun buySelectedPackage(
    client: InappifyV2Client,
    activity: Activity,
    offering: InappifyOffering,
    selected: InappifyPackage,
    productType: InappifyProductType,
    attemptId: String,
): InappifyResult<InappifyPurchase> = client.purchase(
    activity,
    InappifyPurchaseRequest(
        productIdentifier = requireNotNull(selected.product?.identifier),
        offeringIdentifier = requireNotNull(offering.identifier),
        packageIdentifier = requireNotNull(selected.identifier),
        idempotencyKey = attemptId,
        productType = productType,
    ),
)
```

Use `CONSUMABLE`, `NON_CONSUMABLE` or `SUBSCRIPTION` as configured for that SKU.
Generate an attempt ID once for a deliberate purchase, persist it in your host
workflow if retries can survive process restart, and reuse it only for the same
logical store purchase. It must match `[A-Za-z0-9_.:-]{1,128}`. A retry of an
existing paid store attempt resumes its durable work without reopening billing.
A different product, customer or offering cannot reuse that attempt ID.

The verified `storePlatform` controls the route. Leave request `market` at its
default or, for a store, pass the matching market. Leave credential/context
purchase overrides, discount fields and lost-receipt fields unused; the current
client rejects these overrides. No caller supplies a receipt token for recovery.

## Understand purchase completion

The states below describe the backend workflow. PROCESSING/backoff or unfinished
consumption can produce an `InappifyResult.Failure` while preserving the durable
operation, rather than a successful result carrying that state.
`DELIVERY_REQUIRED` can be a successful result whose fulfillment is incomplete.

| Result state | Host action |
| --- | --- |
| `PROCESSING` | Keep a pending UI; the SDK owns verification polling and durable retries |
| `COMPLETED`, `RESTORED`, `ALREADY_PROCESSED` | Refresh/read verified CustomerInfo and check the entitlement |
| `DELIVERY_REQUIRED` | Grant the verified consumable durably exactly once; then confirm |
| `CONSUME_REQUIRED` | Delivery is acknowledged; let the SDK finish store consumption/recovery |
| `REJECTED` | Display the sanitized failure state; do not grant access |
| Direct `NEEDTOPAY` | Validate/open the returned payment URL and recover on return |

Never unlock a subscription based on a native store receipt, URL return or
purchase status alone. Use `client.hasEntitlement("your_entitlement")` for current
verified state or `checkEntitlement` to refresh first.

With the registered delivery handler, the SDK acknowledges only `DELIVERED` and
then consumes a Bazaar/Myket consumable. `RETRY_LATER` preserves pending work.
Without a handler, call `syncPendingConsumables()`, durably grant each returned
`InappifyConsumableDelivery`, and call `confirmDelivery(deliveryId)` only after
that transaction commits. Retried confirmations must reuse the same delivery ID.
Do not combine competing automatic and manual grant paths. The SDK does not infer
the granted quantity from the product identifier.

## Direct checkout

For an app configured as `DirectAndroid`, `purchase(request)` does not require a
store Activity. Supply an offering product as above, keep `market = NONE`, and
optionally use `isCrypto` or positive Direct paywall attribution via
`withPaywallAttribution`. Direct checkout is created by the commerce service.

Open a payment URL only after the SDK allowlist check:

```kotlin
import android.app.Activity
import android.content.Intent
import android.net.Uri
import com.inappify.sdk.*

fun openDirectPayment(
    client: InappifyV2Client,
    activity: Activity,
    purchase: InappifyPurchase,
): Boolean {
    val url = purchase.url ?: return false
    if (!client.isPaymentUrlAllowed(url)) return false
    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    return true
}
```

The production allowlist permits HTTPS on `pay.inappify.com` at port 443 and
rejects URL credentials. Do not log the checkout URL. Browser return and
`purchaseStatus == DONE` are not consumable inventory grants. Call `recover()`
and reconcile pending deliveries; verified CustomerInfo remains the entitlement
source. Direct creation may have committed on a timeout; inspect
`error.details["outcomeMayHaveCommitted"]` and recover instead of blindly creating
another checkout.

## Restore and automatic recovery

After successful Configure, the SDK schedules recovery in the background.
On foreground, restored connectivity and return from checkout, await
`client.recover()` to reconcile lifecycle state, refresh CustomerInfo, sync
pending deliveries/store inventory and refresh offerings. Store reconciliation
is attempted even when CustomerInfo or Offerings refresh fails; the overall
failure remains visible. No recovery method opens billing UI.

`syncPurchases()` returns reconciled purchase results.
`restorePurchases()` is an explicit restore action with independent restored,
already-processed and failed counts. `syncPendingConsumables()` also queries and
continues relevant store work before resolving the host delivery queue.

Bazaar queries owned in-app purchases and subscriptions; Myket queries in-app
purchases. For compatible non-consumable/subscription receipts, fresh verified
CustomerInfo suppresses duplicates only when both token SHA256 and purchase time
match. Pending operations resume before that comparison. A subscription with a
changed purchase time can be submitted again. If both token and time stay
unchanged, the SDK cannot infer a new period from that evidence alone.

Bazaar subscription receipts with a genuinely empty/whitespace developer payload
can use `store/subscriptions/recover`. This requires original signed evidence and
backend ownership/linkage validation; unknown or other-account receipts must be
rejected by the backend. A nonempty malformed or invalidly bound payload never
falls back to this path. `enableSubscriptionRecoveryWithoutPayload` defaults to
true. Set it to false in `InappifyOptions` to disable discovery and polling of
those operations without deleting their checkpoints. Myket and bound receipts
keep their own validated paths.

## Observe state and control networking

Read `client.snapshot`, register `addEventListener`, or collect
`client.stateUpdates()` in a lifecycle-aware host scope. State includes revision,
configuration/identity status, SDK/app version, route, CustomerInfo and offerings;
it does not expose session or purchase tokens. Close listener registrations when
the observing component is destroyed, while keeping the application-scoped
client alive.

`setNetworkEnabled(false)` stops SDK network use while retaining verified cache.
Cache getters can still expose usable state; this switch is not a payment success
or logout. Enable networking and invoke `recover()` when appropriate. Configure
can restore verified persistent state, then reuse or renew its session. The
separate `restoreCachedSession` extension is unsupported by the production
client; it is not a cache-only startup API for this release.

## Handle failures and diagnostics

Every semantic operation returns `InappifyResult.Success` or `Failure`. Handle
`Failure.error.code`, `isRetryable` and sanitized `details`. Coroutine cancellation
can still throw `CancellationException`; invalid use after `close()` is a
programming error. Keep UI cancellation distinct from a verified payment result.

An opt-in `addHttpTraceListener` receives bounded, filtered HTTP traces. Its
callback can run on a background thread. For routine diagnostics record method,
endpoint, status, duration, error category and SDK version. Do not add an OkHttp
logger that prints session tokens, raw receipts, customer IDs or payment URLs.

For discount codes, call `validateDiscountCode` and inspect `isValid` separately
from SDK-call success. A successful response can be an invalid code. The current
purchase method rejects a request-level `discountCode` or nonzero `discount`;
validation is not an instruction to pass those overrides into purchase. See
[the API reference](API_REFERENCE.md) for exact request fields and errors.
