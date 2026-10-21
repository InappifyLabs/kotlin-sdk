# Recovery and cache

The client reconciles store-owned receipts with Inappify and resumes encrypted checkpoints for paid operations. Store inventory, a local cache, and returning from a purchase screen are not entitlement authority. CustomerInfo must pass the SDK's signature and identity/scope checks, and consumables require a verified, idempotent host grant.

This document describes the current Android implementation and the HTTP contract it expects. It does not establish that a particular commerce backend has deployed the subscription-recovery endpoint or that a live store renewal has been tested.

## Startup and automatic recovery

`InappifyClient.create(context)` returns an `InappifyV2Client`. Configure it after resolving the current application account:

```kotlin
import com.inappify.sdk.*

suspend fun configureRecoverySession(
    client: InappifyV2Client,
    inappifyApiKey: String,
    currentAppUserId: String?,
): InappifyResult<Unit> = client.configure(
    InappifyOptions(
        apiKey = inappifyApiKey,
        appUserIdentifier = currentAppUserId,
    ),
)
```

Configure schedules recovery after accepting an online session, reusing a matching unexpired cached session, or completing a pending login. Configure success does not wait for the background recovery result. The SDK also observes process foreground through AndroidX and restored internet through Android connectivity monitoring. Networking must be enabled and internet available for automatic recovery admission.

On foreground or restored internet, a recovery pass is eligible when the current
customer has unfinished purchase checkpoints, no successful pass has completed
in this client, or at least five minutes have elapsed since the last successful
pass. A failed pass blocks automatic retries for at least thirty seconds, even
when a pending purchase exists. A later qualifying event retries; no periodic
timer is started. Configure/Login retain startup scheduling, subject to the same
network availability and failure backoff. A new accepted customer gets its own
schedule. Reenabling SDK networking checks the same event condition.

Pending native operations must match store, endpoint/API-key fingerprint,
customer fingerprint, app ID and Android package. For DirectAndroid, unfinished
delivery records in the current identity-bound document count; completed records
do not. These checks only read existing checkpoints and never query the store or
renew the session to decide eligibility. Previously unknown owned receipts or
server deliveries are discovered by startup, a due pass or a manual purchase sync.

Recovery clocks use monotonic time and live only in memory. No session-cache or
purchase-journal fields are added. [Android connectivity monitoring](https://developer.android.com/develop/connectivity/network-ops/reading-network-state)
uses the merged normal
`ACCESS_NETWORK_STATE` permission, with no runtime permission prompt. Initial
online status and duplicate connectivity callbacks are not reconnect events.

Do not manually pre-warm Offerings in `Application.onCreate`. The Offering step
of automatic recovery waits until the app is visible and the catalog is missing
or at least five minutes old. Entering the foreground also checks this condition.
These checks do not create a periodic timer. A push-only process start does not
start automatic Offering prefetch. Explicit SDK calls and existing customer and
purchase recovery can still use the network in the background.

For `Bazar` and `MyKet`, internal automatic recovery runs these steps:

1. Resolve a pending logout or login before ordinary work.
2. Refresh CustomerInfo for the current session.
3. Resume matching pending store operations and query owned inventory, including consumable delivery work.
4. Refresh Offerings after store reconciliation, using the existing foreground/freshness checks for automatic passes.

A CustomerInfo refresh failure does not prevent the store reconciliation attempt. In that case, stale cached entitlements are not used to discard newly discovered receipts. Offerings failure also does not prevent the preceding store query. The returned recovery failure still reports failed work; the client does not convert partial progress into a successful full refresh.

For `DirectAndroid`, recovery refreshes CustomerInfo and Offerings before reconciling the server consumable-delivery queue. Other session store platforms do not acquire Bazaar or Myket billing automatically.

Foreground and connectivity observation are automatic. Recovery is internal and
is not a public SDK method. For a manual purchase check, call
`client.syncStorePurchases()`; for a user-initiated Restore Purchases action, call
`client.restoreStorePurchases()`. Neither method requires parameters.

Configure and Login register automatic recovery immediately with one client-owned
coordinator. Concurrent events for the accepted customer binding share a pass.
Configure/Login success does not wait for recovery to finish.

When an accepted customer binding changes, one new pass is queued after the
active pass. Further calls for that new binding join its queued pass. A stage
from the previous binding cannot perform I/O using the new customer's session;
the superseded pass reports `RECOVERY_SUPERSEDED` if it still has work to do.
Pending identity barriers and rejected Login attempts retain their existing
semantics. The coordinator's identity generation exists only in memory; it adds
no fields to session cache or purchase journals.

Canceling one waiter does not cancel client-owned recovery. Closing the client
cancels active and queued passes and releases billing resources and both process
and connectivity observers. Foreground/reconnect events during an active pass
reuse that work rather than queueing retries behind it. Purchase uses the
checks below. There is no periodic
background worker or guaranteed recovery while the application is closed.

### InAppify payment-return links

For requests advertising payment-return capability, Laravel adds these reserved
query fields to the configured native app return URL:

```text
myapp://any-route?existing=value&inappify_return=payment_v1&inappify_app_id=12&inappify_return_id=payment-123#screen
```

The app keeps its scheme/host/path, existing query fields and fragment. Native
actions in purchase links use the selected native app ID; PWA actions stay
unchanged. The marker is a synchronization hint, not a signature or proof that
payment succeeded. The SDK ignores any `status`/transaction claims as payment
authority and recovers using its authenticated current session.

The new SDK internally sends `paymentReturnVersion: 1` on direct checkout and
discount-link requests. No extra Configure parameter or persisted cache field is
needed. Go adds `inappify_payment_return=1` and the authenticated app ID as
`inappify_return_app_id` to capable discount checkout links. Checkout retains that
hint through discount changes; Laravel marks only the matching registered native
target. Old SDK requests, ordinary purchase links and existing return snapshots
keep their previous URLs exactly. The return URL is decided when the payment is
created; callback rendering never retrofits markers. Unknown capability versions
fall back to unmarked links. Deploy Laravel/checkout, then Go, before the new SDK.

SDK-created clients observe Activity VIEW intents and AndroidX
[new-intent callbacks](https://developer.android.com/reference/androidx/core/app/OnNewIntentProvider).
Create the application-scoped client before Activity startup for automatic cold
intent observation. A custom router, late initialization, or a plain Activity
without `OnNewIntentProvider` should forward URLs explicitly:

```kotlin
intent.dataString?.let(client::handleDeepLink)
// In a custom Activity's onNewIntent, forward that new Intent's dataString too.
```

`handleDeepLink(url)` returns whether the reserved payment-return marker was
recognized/queued; it does not indicate payment success or completed sync. It
can receive all routed links: ordinary links, unsupported versions, malformed
or duplicate reserved fields and another configured app's ID do not force
recovery. Host navigation/Intent data is never changed. The existing general
foreground freshness rule still applies independently.

A matching marker forces a fresh recovery without waiting five minutes.
Startup work admitted after the marker can be shared; a pass admitted before
the return finishes before one fresh pass is requested. Concurrent/recently
successful return IDs are deduplicated in bounded runtime memory. Before
Configure or while offline/disabled, the hint waits for a valid session/network.
The thirty-second recovery error backoff still applies; a pending return can
wait out that delay without blocking the host UI. Failures remain retryable by
a later link/lifecycle/network event. Closing the client removes observers and
cancels deferred work. No cache/journal schema or grant/consume logic changes.

### Before a new purchase

Every purchase overload waits for an already active recovery pass for its
accepted customer. Waiting happens outside the state mutex, so recovery can
finish. Canceling the purchase waiter leaves client-owned recovery running;
closing the client cancels both. If the accepted customer changes while waiting,
purchase fails with `PURCHASE_CONTEXT_CHANGED` before opening payment UI.

A joining purchase can return the matching paid result from that pass, including
when recovery has already removed its completed checkpoint. The pass retains
these outcomes only in memory for its waiters; subsequent deliberate purchases
do not reuse a terminal purchase cache. A recovery failure for CustomerInfo,
Offerings or another product does not override a valid matching purchase result.
Normal session/catalog validation still applies when starting fresh work.

Without a matching joined result, the SDK checks existing paid work for this
product and current customer/app/store/endpoint. A native checkpoint resumes
through the existing store coordinator and returns its existing typed status or
failure. Its original Offering and Package remain attached, even when the current
catalog changed. Explicit attempt IDs keep their existing binding/conflict
validation. Verification, delivery and consumption backoff remain authoritative;
an unresolved attempt does not open a second billing UI.

For DirectAndroid, a known unfinished delivery for this product returns
`DELIVERY_REQUIRED`, or resumes acknowledgement when a host grant was already
durably marked. Purchase does not invoke a new host grant or consume an item on
that basis. Other products' deliveries and completed Direct records do not block
a fresh checkout. The usual delivery-handler/`confirmDelivery` flow still owns
fulfillment.

These checks do not start a full recovery, scan store inventory or fetch the
server delivery queue before every purchase. Startup, foreground/reconnection
recovery and explicit sync discover previously unknown work. Direct checkout
URLs and uncertain checkout creation are not durable delivery checkpoints;
return from payment still requires explicit recovery. No request fields,
purchase result types, receipt evidence or cache/journal formats change.

### Related public methods

| Method | Behavior |
| --- | --- |
| `syncStorePurchases()` | Reconcile pending operations and owned inventory for the current native store, or the delivery queue for `DirectAndroid`; refresh CustomerInfo after success |
| `restoreStorePurchases()` | Host-initiated restore of owned subscriptions/known non-consumables with independent restored/already-processed/failed counts; refresh CustomerInfo after success |
| `syncPendingConsumables()` | Reconcile native receipts and delivery checkpoints, invoke a registered delivery handler, and return remaining deliveries |
| `confirmDelivery(deliveryId)` | Persist host acknowledgement, send it to Inappify, then perform store consumption/reporting |
| `setNetworkEnabled(false)` | Block SDK/commerce HTTP use while retaining verified cache and journals |

Use Configure's validated cache path and the fetch policies below. Configure may need the network to establish or renew its session.

## Owned inventory and bound receipts

Bazaar queries subscriptions with Poolakey `getSubscribedProducts`, then in-app purchases with `getPurchasedProducts`. Myket queries only in-app inventory. The adapters keep locally valid receipts when another receipt is invalid; explicit restore counts invalid receipts as failures. Store login/setup errors remain query failures.

Before discovering new work, reconciliation resumes journals matching the current store, endpoint/app fingerprint, customer fingerprint, Android application identifier, and app ID. A pending checkpoint is not skipped merely because the server already lists an entitlement: verification, host delivery, acknowledgement, consumption, or consumption reporting can still be unfinished.

For an owned receipt with a nonblank developer payload, the client requires a JSON object with a matching `recoveryBinding`, product identifier, offering identifier, and valid attempt identifier. If `purchaseBinding` is present, its app/customer/SKU/offering/package binding must also match the original `nativePackageIdentifier`. A receipt's package must match the installed application. Catalog changes do not substitute a new offering for a paid receipt's original binding.

The client does not rewrite original JSON, signature, developer payload, purchase token, or purchase time to manufacture ownership. A malformed payload, mismatched binding, mismatched SKU/package, conflicting explicit type, or non-string type field cannot fall back to subscription recovery without a payload.

### Missing product type

For a bound receipt, an absent, JSON-null, empty, or whitespace-only `productType` does not invalidate subscription inventory. A genuine subscription query supplies `SUBSCRIPTION` as the local category. An in-app query cannot distinguish a consumable from a non-consumable; an unknown in-app category still goes through server verification during ordinary synchronization.

An explicit type must agree with the queried category. Subscriptions cannot be inferred from in-app inventory. Explicit restore only creates new in-app restore work when the payload declares `NON_CONSUMABLE`; it does not use an unknown or consumable item as a new restore grant. Existing paid checkpoints can still finish their original flow.

## Duplicate checks and renewals

Ordinary automatic synchronization skips a newly discovered non-consumable or subscription receipt only when the same entitlement in freshly verified CustomerInfo contains both:

- `purchaseStoreRefHash` equal to lowercase hexadecimal SHA-256 of the UTF-8 store purchase token.
- `purchaseStoreTime` equal to the receipt's exact millisecond purchase time.

`isActive` is not part of this duplicate check. Consumables and unknown in-app products still go to the server; entitlement metadata cannot prove that host delivery and store consumption finished. Explicit restore forwards eligible receipts instead of applying this automatic entitlement shortcut.

In the current query pass, in-app receipts are deduplicated by token, while subscriptions are deduplicated by token and purchase time. A subscription with the same token but a new purchase time is a separate occurrence. When it collides with a matching existing subscription attempt that has a different known time, the SDK creates a scoped local `renewal:` checkpoint instead of overwriting the earlier paid journal. An existing checkpoint with unknown purchase time conservatively blocks generating another occurrence from that token while its work resumes.

A local occurrence identifier is not sent as a new customer-selected ownership claim. The backend must provide idempotent receipt verification and renewal handling. If the store returns the same token and time for a renewal, the client cannot distinguish it through this comparison; that case needs server/store-side validation. The client also cannot query already-consumed purchases that the store no longer returns without a surviving checkpoint or server delivery record.

Bazaar query collection uses a two-second callback idle window and deduplicates callback pages by token. Delayed pages beyond that window are an inventory limitation; the implementation is not proof of complete pagination under every device/network condition.

## Bazaar subscriptions without a developer payload

Subscription recovery remains enabled internally. It uses a separate, server-resolved recovery operation only for a receipt returned by Bazaar's subscription query with a blank developer payload.

The receipt must still have the correct Android package, a nonblank token, a positive millisecond purchase time, and the original signed store JSON/signature. The regular bound receipt path remains unchanged. This workflow does not cover Myket, in-app purchases, malformed nonblank payloads, or invalid bindings.

### Request contract

The default commerce origin is `https://api.inappify.com/app/v2/`. A separately configured commerce origin must also be HTTPS with the exact `/app/v2/` root. All store requests below use `POST`, including verification-status polling.

```http
POST /app/v2/store/subscriptions/recover
Authorization: Bearer <current-sessionToken>
Accept: application/json
Content-Type: application/json
```

```json
{
  "productIdentifier": "subscription-sku",
  "purchase": {
    "token": "<store-purchase-token>",
    "purchaseTime": 1730000000000,
    "orderId": "<store-order-id>",
    "packageName": "com.example.app",
    "developerPayload": "",
    "originalJson": "<original-store-json>",
    "signature": "<store-signature>"
  }
}
```

The body preserves the receipt evidence. `purchaseTime` is milliseconds, not seconds; `orderId` is omitted if absent. Blank developer payload text is retained as received, so it may contain whitespace. The SDK sends no offering selection, package selection, app/customer ID, operation string, product type, API key, customer token, or session token in this body. The session token is the Bearer credential. `apiKey` is used for Configure/session renewal; it is not the commerce credential.

The SDK generates a deterministic `subscription-recovery:` journal identifier scoped to store, Android package, app ID, endpoint/app fingerprint, current customer, SKU, token, and time. No receipt is relabeled to another customer, and that identifier is not a substitute for backend idempotency.

### Ownership/linkage and response contract

The service must resolve the subscription's previously established ownership and product/offering linkage using authenticated session context and verified store evidence. It must not assign an unlinked receipt to whoever currently asks to recover it, or trust a client-selected offering/entitlement. The SDK cannot establish that missing ownership locally.

Initial registration requires a successful envelope with the state under `data.purchase`, for example:

```json
{
  "status": true,
  "data": {
    "purchase": {
      "status": "PROCESSING",
      "verificationRequestId": 123,
      "source": "bazar",
      "productIdentifier": "subscription-sku",
      "retryAfter": 2
    }
  }
}
```

For this recovery operation, every accepted registration/status response must identify `source: "bazar"` and the exact `productIdentifier`. Verification IDs must remain consistent when already established. The accepted subscription flow is `PROCESSING` followed by `COMPLETED`, `RESTORED`, `ALREADY_PROCESSED`, or `REJECTED`. Processing needs a positive verification request ID and is polled through the ordinary store verification route. A subscription response requesting consumable delivery/consumption, returning an unrelated delivery ID, or changing scope fails closed and is not permission to grant access.

Fields are decoded strictly: identifiers are positive integer numbers, `retryAfter` is a nonnegative integer, boolean fields are JSON booleans, and text fields are strings. Missing/null optional fields are allowed only where the workflow does not need them. Malformed or contradictory responses preserve unresolved work rather than inventing a success.

A permanent error can use a failed envelope with a symbolic `code`, such as `SUBSCRIPTION_NOT_LINKED`. The recognized permanent recovery codes are:

| Code | Meaning the backend must enforce |
| --- | --- |
| `SUBSCRIPTION_NOT_LINKED` | No established subscription/product ownership linkage can be recovered |
| `SUBSCRIPTION_OWNER_MISMATCH` | Evidence belongs to a different owner than the authenticated session |
| `SUBSCRIPTION_EVIDENCE_INVALID` | Store evidence fails subscription verification |
| `SUBSCRIPTION_BINDING_INVALID` | The recovered subscription linkage is invalid |

These named rejections and an explicit `REJECTED` state finalize the matching pending operation with an encrypted, scoped rejection marker. Authorization failures, missing endpoints, temporary failures, and malformed responses are not proof of permanent invalid evidence. A missing backend endpoint is an integration failure; it does not trigger a weaker purchase request or a locally fabricated offering.

## Other store requests and durable checkpoints

All commerce requests authenticate with the current matching session's Bearer token. Request DTOs do not send API keys or a saved customer token in their bodies.

| Relative commerce route | JSON body |
| --- | --- |
| `store/purchases` | `productIdentifier`, `offeringIdentifier`, `operation` (`purchase` or `restore`), and `purchase` evidence with `token` plus available `purchaseTime`, `orderId`, `packageName`, `developerPayload`, `originalJson`, `signature` |
| `store/subscriptions/recover` | `productIdentifier` and the original `purchase` evidence; no offering/operation fields |
| `store/verifications/{verificationRequestId}/status` | `{}` |
| `store/deliveries/{deliveryId}/delivered` | `{}` |
| `store/deliveries/{deliveryId}/consume-result` | `result` (`succeeded` or `failed`) and optional `errorCode` |

The coordinator persists a locally accepted paid receipt before its first registration request. It checkpoints registration, verification, delivery required, delivery confirming, consume required, and consume-result reporting. A process restart can therefore resume the correct next step instead of relaunching billing.

For a consumable:

1. Inappify verifies the receipt and returns `DELIVERY_REQUIRED` with a delivery ID.
2. The host durably grants the item exactly once, keyed by that delivery ID.
3. `confirmDelivery`, or a registered handler's `DELIVERED` result, persists acknowledgement before HTTP and calls the delivered route.
4. Only after that acknowledgement does the coordinator consume the same store receipt.
5. The consumption outcome is persisted before reporting it to the consume-result route. A retained successful consume result is reported again after interruption rather than consuming the purchase again.

The SDK never grants credits itself and never consumes a subscription or non-consumable. The host must not call confirmation before its inventory transaction commits. Without a delivery handler, recovery leaves verified grants pending for host fulfillment. A handler returning `RETRY_LATER`, or throwing an ordinary exception, keeps that grant pending. See [store integration](STORES.md) and the [consumable API](../sdk/src/main/kotlin/com/inappify/sdk/InappifyConsumableFulfillment.kt).

### Retries and uncertain outcomes

Commerce transport does not blindly replay writes after network failure. The durable coordinator owns resubmission/polling. It persists retry timing with exponential waits starting at two seconds, capped at fifteen seconds before a larger server `Retry-After`/state delay, plus up to 250 ms jitter. A wait beyond fifteen seconds is deferred for a later call rather than keeping the current process waiting indefinitely.

Coordinator submissions allow up to eight verification polls, normal resumes default to one, and foreground retry/confirmation can use eight. These are per-operation limits; reconciliation may process multiple operations. Store consumption has a 30-second coordinator timeout. Work remaining after the limit stays pending.

Network/timeouts and HTTP 408, 425, 429, or server errors are retried through retained checkpoints. Authorization failures do not erase paid evidence. An expired commerce session is renewed before use; one HTTP 401 with the explicit `SESSION_EXPIRED` code permits renewal and one authenticated replay. If renewal changes the store route, replay stops with `PURCHASE_ROUTE_CHANGED` instead of registering the receipt under another store. Other authorization failures are surfaced without a permanent rejection marker.

`outcomeMayHaveCommitted` signals that the app should preserve the attempt and reconcile before allowing another payment. Reuse the original valid idempotency key for an unresolved bound purchase. Explicit store cancellation is separate from uncertain callback/network failure.

## Cache freshness and account scope

Session state and the store journal are encrypted separately with AES-GCM and Android Keystore-backed keys, stored using atomic files in `noBackupFilesDir`, and guarded by file locking. The production session file is `inappify_go_session_v2.bin`; the store journal is `inappify_pending_store_operations_v2.bin`. These names, key aliases, and binding fingerprints are persistent runtime namespaces; changing an artifact version or repository name is not a reason to rename them.

Cache acceptance validates the session's public type, signed CustomerInfo, trusted signing key, issuer/app/project scope, public app-user identifier, internal customer subject, and data model. A cache cannot supply trust for an unknown signing key. Configure may fetch `public-keys` to recognize a key that matches an existing trust anchor; downloaded keys cannot establish a new anchor.

A matching, unexpired cached session with unchanged country/app version can avoid a Configure HTTP request and still schedule recovery. Expired/invalid sessions need renewal. Cached CustomerInfo may be displayed with an expired signature as previously verified cache, but that is not current server authorization. A configured snapshot or `verifiedSessionScope` also does not prove fresh purchase completion.

| Fetch policy | CustomerInfo and Offerings behavior |
| --- | --- |
| `CACHE_ONLY` | Return available cached data; fail with `CACHE_MISS` otherwise |
| `CACHE_FIRST` | Return available cache immediately; CustomerInfo schedules its existing refresh, Offerings refreshes automatically only when foreground and stale; a cache miss uses the network |
| `NETWORK_FIRST` | Try the network and fall back to available cache on failure |
| `NETWORK_ONLY` | Use the network and report failures |

`getCustomerInfo()` defaults to `InappifyCacheFetchPolicy.CACHED_OR_FETCHED`: return
verified cache immediately and refresh it when stale, or wait for the server when
cache is absent. CustomerInfo is fresh for five minutes in the foreground and
twenty-five hours in the background. This reuses the existing persisted
`infoFetchedAt` timestamp, including after restart. Foreground transitions and
restored connectivity also check freshness; no polling timer is created.
`CACHE_ONLY` never fetches. `FETCH_CURRENT` always waits for the server.
`NOT_STALE_CACHED_OR_CURRENT` accepts fresh cache or waits for the server; network
failure remains a failure even if stale cache is retained. Failed refresh never
replaces verified cache. Advanced `customerInfo(policy)` keeps its explicit
cache/network behavior, including network-first
fallback; purchase, entitlement checks and recovery retain forced refreshes.

`entitlements.all` includes active, expired and inactive known rights;
`entitlements.active` checks active status and expiry against the trusted clock
at access time. `entitlements[identifier]` looks up any known right. These views
do not fetch or write cache, and the existing nullable array encoding is retained.

`getOfferings()` uses `CACHE_FIRST`: its cached result returns immediately, and automatic refresh is eligible after five minutes, only in the foreground. `getCurrentOffering(placementIdentifier)` uses the same cache/fetch flow before selection and has no `forceRefresh` parameter. Automatic Offering failures wait thirty seconds before the next foreground/read trigger retries. Concurrent automatic work is shared and rechecks freshness after acquiring the state lock. An explicit cache-miss read, `NETWORK_ONLY`, `NETWORK_FIRST` or admitted `syncAttributesAndOfferingsIfNeeded()` call still uses the network without this automatic delay. Use the combined sync method for manual refresh; it retains its separate five-per-minute limiter, which automatic refresh does not consume.

Offering freshness and failure timestamps use a monotonic clock in memory only.
No persisted cache fields, versions, scopes, fingerprints or store journals
change. A restored disk catalog remains immediately usable and is considered
eligible for one foreground validation because its runtime fetch time is unknown.
A failed refresh retains previously published cache. A country/app-version change
invalidates cached Offerings before reuse, and accepting a new session clears
Offerings so server targeting can be fetched again. Offerings fetch flushes queued
attributes first. Closing the client unregisters its process-lifecycle observer.

Omitting `appUserIdentifier` during Configure can reuse the persisted identity, including a signed-in account; it is not an instruction to switch to an anonymous user. Supply the host's intended identity, and use login/logout deliberately. Resolve pending identity transitions before presenting account-specific purchasing UI. During pending logout or login, customer identity/CustomerInfo/Offerings are withheld and purchase work is blocked.

Native journals outlive session transitions. They are not reassigned when another user logs in: only matching customer/app/store/endpoint-bound work resumes, using the current matching session token rather than a stale persisted token. Returning to the same account can resume its paid operations. The host should retain its own account-generation guard around asynchronous UI updates and durable grants.

### Storage errors and downgrades

A transient file/Keystore failure preserves encrypted recovery evidence and blocks unsafe replacement. Invalid envelope/authentication-tag or structurally corrupt journal data can be discarded by the storage implementation. Storage bounds are 256 pending operations, 256 rejection markers, and a four-MiB plaintext journal limit; inability to persist a required checkpoint is a failure, not permission to consume or begin another payment.

Rejection markers contain scoped one-way fingerprints rather than plaintext purchase tokens, and are bounded. They suppress the exact rejected evidence/context, not every future renewal or every receipt with the same product.

A reader that cannot decode `RECOVER_SUBSCRIPTION` is not a supported downgrade target. The decoder skips unrecognized operation values; a client that later rewrites the shared journal can omit unfamiliar records. Unknown schema versions also are not accepted as ordinary pending state. Do not claim durable recovery compatibility merely because the journal filename/schema number remains unchanged, and do not erase or manually rewrite paid state to work around an error. Prefer disabling the optional recovery flag while keeping a client that understands the journal.

## Implementation references

- [Client Configure, recovery, cache policies, receipt reconciliation and account transitions](../sdk/src/main/kotlin/com/inappify/sdk/internal/v2/GoV2Client.kt), [public V2 methods](../sdk/src/main/kotlin/com/inappify/sdk/InappifyV2.kt), and [recovery option](../sdk/src/main/kotlin/com/inappify/sdk/InappifyOptions.kt).
- [Exact store wire fields](../sdk/src/main/kotlin/com/inappify/sdk/internal/v2/GoV2StoreService.kt), [transport/authentication and signed CustomerInfo validation](../sdk/src/main/kotlin/com/inappify/sdk/internal/v2/V2Protocol.kt), and [store backend/state types](../sdk/src/main/kotlin/com/inappify/sdk/internal/network/StoreV2Backend.kt).
- [Durable coordinator and response/retry validation](../sdk/src/main/kotlin/com/inappify/sdk/internal/StoreV2Coordinator.kt), [journal model](../sdk/src/main/kotlin/com/inappify/sdk/internal/storage/PendingStoreOperation.kt), [journal decoder](../sdk/src/main/kotlin/com/inappify/sdk/internal/storage/PendingStoreOperationCodec.kt), and [encrypted storage](../sdk/src/main/kotlin/com/inappify/sdk/internal/storage/EncryptedSessionStateStore.kt).
