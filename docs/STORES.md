# Bazaar and Myket

The SDK provides Android billing adapters for Cafe Bazaar and Myket. A store receipt is evidence for server verification; it does not grant an entitlement by itself. The configured session's `storePlatform` selects the purchase route.

| Session store platform | Products supported by this SDK | Owned inventory queried |
| --- | --- | --- |
| `Bazar` | Consumable, non-consumable, subscription | Bazaar subscriptions, then in-app purchases |
| `MyKet` | Consumable, non-consumable, manually renewed timed subscription | Myket in-app purchases |
| `DirectAndroid` | Server checkout; no native store UI | Server delivery queue |

These native adapters run on Android. They do not implement iOS billing, Google Play Billing, or App Store billing. Myket timed subscriptions use ordinary in-app purchases, with duration and manual renewal managed by Inappify. They are not native/auto-renewing Myket subscriptions.

## Install and configure the store application

Install the SDK as described in the [README](../README.md). The SDK dependency includes Poolakey for Bazaar and an internally namespaced Myket billing implementation. The host does not need to add a separate Myket billing library or manually copy billing activities, receivers, or AIDL classes. Adding another billing implementation is not required for this SDK's flows.

Before testing a store build:

1. Register the Android application and its product identifiers in the chosen store, and use the application package and signing identity associated with that registration.
2. Configure the corresponding application, offering, package, and product in Inappify. `productIdentifier` must identify the store SKU; `packageIdentifier` identifies the Inappify package selected from Offerings.
3. Install the chosen store application on the device and sign in to an account eligible to buy or query the products. A missing store application, unavailable billing service, or account that cannot return inventory produces a store error.
4. Save the application's Base64 RSA public billing key from the chosen store in Inappify. Configure returns it as `storeInfo`, and the SDK persists it with the session for billing and recovery.

The Inappify `apiKey` initializes the session. The server supplies the store RSA public key for local receipt verification; the app does not pass it to Configure. Never supply a private RSA key, account password, or manually acquired store purchase token as configuration.

## Manifest merging

The AARs provide their own manifest entries. Keep Android manifest merging enabled and inspect the final application's merged manifest if a store cannot be discovered or billing cannot start.

| Entry | Where it comes from | Purpose |
| --- | --- | --- |
| `android.permission.INTERNET` | SDK manifest | Inappify requests |
| `com.farsitel.bazaar.permission.PAY_THROUGH_BAZAAR` | Poolakey manifest | Bazaar billing |
| Package query for `com.farsitel.bazaar` | Poolakey manifest | Bazaar package visibility |
| `ir.mservices.market.BILLING` | SDK manifest | Myket billing |
| Package query for `ir.mservices.market` and intent query for `ir.mservices.market.InAppBillingService.BIND` | SDK manifest | Myket package/service visibility |
| `com.inappify.sdk.internal.billing.myket.util.ProxyBillingActivity` | SDK manifest | Myket purchase result routing; `exported=false` |
| `com.inappify.sdk.internal.billing.myket.util.IABReceiver` | SDK manifest | Myket billing broadcasts; `exported=true` with declared billing actions |
| `ir.cafebazaar.poolakey.receiver.BillingReceiver` | Poolakey manifest | Bazaar billing broadcasts |

These billing permissions are manifest permissions, not runtime permission dialogs. Both stores' entries can exist in the same APK; only the adapter selected by the session is used. The SDK has no store-specific Gradle flavor or manifest placeholder that the host must set. If the host removes or overrides library manifest entries, it must retain the entries required by its chosen billing flow.

## Configure once for the current customer

Create a long-lived client with `InappifyClient.create(applicationContext)`, then configure it after determining the current app-user identity:

```kotlin
import com.inappify.sdk.InappifyOptions
import com.inappify.sdk.InappifyResult
import com.inappify.sdk.InappifyV2Client

suspend fun configureBazaarStore(
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

Both Bazaar and Myket obtain their route and RSA public key from the server session. Configure only accepts the app API key and optional customer identity.

No market override is accepted by Configure. The server returns `storePlatform` for the configured application. Confirm that `client.snapshot.storePlatform` is `Bazar` or `MyKet` as intended. A request-level market cannot switch a server-bound purchase to another store.

Configure may return after reusing a valid cached session. Its automatic recovery work continues in the background. Wait for an Offerings result or event before constructing a purchase from the current catalog. See [recovery and cache](RECOVERY_AND_CACHE.md) for startup behavior and explicit reconciliation.

## Host the purchase UI in an Activity

A new paid store purchase requires `client.purchase(InappifyPurchaseParams.Builder(activity, selectedPackage).build())`. The Activity must implement both AndroidX `ActivityResultRegistryOwner` and `LifecycleOwner`, and must not be finishing or destroyed. `ComponentActivity`, `FragmentActivity`, and `AppCompatActivity` satisfy the interface requirements.

Use the visible checkout Activity for each invocation. Passing an application context or calling the overload without an Activity cannot start a new store purchase. The SDK keeps an invocation-scoped weak Activity reference, registers lifecycle-bound result handling, and cancels the operation if that host is destroyed. Store inventory queries and durable verification recovery do not need an Activity.

Both adapters perform their billing setup/UI calls on the Android main thread; the public API is suspending. Coroutine cancellation propagates to the caller, and adapter cleanup disconnects or disposes billing resources. Closing the client cancels its work and billing adapters. Recreate a client after `close()`; do not close it after every checkout.

## Purchase a catalog package

Use the Package returned by the selected Offering. The SDK reads the product
type supplied by Go V2 and adapts to the existing store purchase request:

```kotlin
import android.app.Activity
import com.inappify.sdk.*

suspend fun buyStoreSubscription(
    client: InappifyV2Client,
    activity: Activity,
    selectedPackage: InappifyPackage,
    logicalPurchaseAttemptId: String,
): InappifyResult<InappifyPurchase> = client.purchase(
    InappifyPurchaseParams.Builder(activity, selectedPackage)
        .idempotencyKey(logicalPurchaseAttemptId)
        .build(),
)
```

| `InappifyProductType` | Bazaar billing call | Myket billing call | Fulfillment |
| --- | --- | --- |
| `CONSUMABLE` | `purchaseProduct` / `IN_APP` | In-app purchase | Verified host delivery, acknowledgement, then consumption |
| `NON_CONSUMABLE` | `purchaseProduct` / `IN_APP` | In-app purchase | Server-confirmed ownership; no consumption |
| `SUBSCRIPTION` | `subscribeProduct` / `SUBSCRIPTION` | `IN_APP`; consume only after server grant authorization | Server-confirmed entitlement; only Myket consumes |

The package-based API requires a known `product.productType` for native stores.
Missing or unknown metadata returns `PRODUCT_TYPE_REQUIRED` before opening the
store; refresh offerings and ensure the product is classified in the catalog. An
unbound package has no parent offering and returns
`PURCHASE_PACKAGE_CONTEXT_REQUIRED`. No product or offering is guessed from
another catalog entry. Parent context is reconstructed after cache restoration.

The public purchase API accepts `InappifyPurchaseParams` only. Product, Offering,
Package and classification come from the selected catalog package.

If supplied, `idempotencyKey` must match `[A-Za-z0-9_.:-]{1,128}`. Generate one value per logical purchase and retain it while that attempt is unresolved. If the corresponding encrypted checkpoint exists, retrying the same request and key resumes server verification without opening billing again. Reusing a key with a different product, offering, type, customer, app, or store fails with `PURCHASE_ATTEMPT_CONFLICT`. The host must not interpret a timeout or missing callback as permission to create another paid attempt.

Store purchases use the configured session and key. Request-level `apiKey`, `marketKey`, `country`, `appVersion`, lost-purchase flags, dynamic-price tokens, discounts, crypto flags, and paywall attribution are rejected for this route. Automatic inventory reconciliation owns recovery; the host does not submit raw lost-purchase tokens.

## Results, cancellation, and uncertain outcomes

The adapters validate package/SKU/receipt fields locally before forwarding evidence. Myket also validates the original JSON's RSA signature before accepting a receipt. Bazaar enables Poolakey's RSA security check. A locally accepted receipt still requires Inappify verification.

A successful result may be `COMPLETED`, `RESTORED`, `ALREADY_PROCESSED`, or `DELIVERY_REQUIRED`; inspect `storePurchaseStatus`. Verification that remains `PROCESSING` is retained durably and can be returned as a retryable pending failure. Entitlement access must come from CustomerInfo, and consumable credits must come from a verified delivery.

An explicit store cancellation maps to `PURCHASE_CANCELLED`. Connection loss, Activity destruction, timeout, invalid receipt data, or a lost callback can leave the payment outcome uncertain. Check `error.details["outcomeMayHaveCommitted"]`, keep the same logical attempt, and run recovery instead of immediately asking the customer to pay again. A coroutine `CancellationException` is cancellation of SDK work, not proof that the store reversed a payment.

Useful failure details include `store`, `storeCode`, `attemptId`, `httpStatus`, and a bounded, redacted backend code when available. Examples of setup failures are a missing/invalid key, unsupported Activity, unavailable store, and a mismatched server route. Do not log receipt JSON, tokens, signatures, developer payloads, API keys, or payment URLs.

## Consumables and restore

Grant a verified consumable exactly once in host-owned durable storage using `deliveryId` as the unique grant key. After that transaction commits, call `client.confirmDelivery(deliveryId)`, or register an `InappifyConsumableDeliveryHandler` that returns `DELIVERED` only after the same durable grant. Returning `RETRY_LATER` or throwing keeps the delivery pending. The SDK acknowledges the grant, consumes the store receipt, and reports consumption to Inappify. It does not infer credit amounts or grant the item for the host.

`client.restoreStorePurchases()` returns independent restored/already-processed/failed counts and attempts to reconcile subscriptions and known non-consumables. It does not treat owned consumable inventory as a new restore grant. Pending paid operations can still finish their existing verification and delivery checkpoints. `syncStorePurchases()` and internal automatic recovery handle interrupted purchases, including consumables. Their behavior and duplicate checks are described in [recovery and cache](RECOVERY_AND_CACHE.md).

## Device and inventory limits

Bazaar queries `getSubscribedProducts` and `getPurchasedProducts`. Myket calls its in-app inventory query only; there is no Myket subscription inventory path in this SDK. The recovery queries can keep valid items when another receipt is invalid. Explicit restore includes invalid receipt counts in `failedCount`.

Bazaar's adapter combines query callbacks and completes after a two-second callback idle window, deduplicating returned pages by purchase token. This is a timing assumption, not a guarantee that an arbitrarily delayed page will be included. Myket operations are serialized across SDK helpers because its broadcast callbacks are application-scoped, and an operation has a 120-second timeout. The coordinator separately bounds store consumption to 30 seconds.

Test the final store-specific APK, matching signing/package identity, store account, canceled checkout, interrupted verification, restore, and consumable delivery on a real device. This document describes the current implementation and does not certify a live store transaction or a particular backend deployment.

## Implementation references

- [Store route/query mapping](../sdk/src/main/kotlin/com/inappify/sdk/internal/billing/StoreMarket.kt), [adapter factory](../sdk/src/main/kotlin/com/inappify/sdk/internal/billing/AndroidStoreBillingAdapterFactory.kt), and [SDK manifest](../sdk/src/main/AndroidManifest.xml).
- [Bazaar adapter](../sdk/src/main/kotlin/com/inappify/sdk/internal/billing/BazaarStoreBillingAdapter.kt), [Myket adapter](../sdk/src/main/kotlin/com/inappify/sdk/internal/billing/MyketStoreBillingAdapter.kt), [Myket receipt validation](../sdk/src/main/kotlin/com/inappify/sdk/internal/billing/MyketReceiptValidator.kt), and [Activity requirements](../sdk/src/main/kotlin/com/inappify/sdk/internal/billing/StoreUiHost.kt).
- [Configure/purchase/reconciliation client](../sdk/src/main/kotlin/com/inappify/sdk/internal/v2/GoV2Client.kt), [product types](../sdk/src/main/kotlin/com/inappify/sdk/InappifyProductType.kt), [store statuses](../sdk/src/main/kotlin/com/inappify/sdk/InappifyStorePurchaseStatus.kt), and [consumable API](../sdk/src/main/kotlin/com/inappify/sdk/InappifyConsumableFulfillment.kt).

## Myket timed subscriptions (Kotlin 0.2.2 / Flutter 0.2.3)

Follow [Myket's Java documentation](https://myket.ir/kb/pages/java/): Myket does not offer native subscriptions. Register a **consumable** SKU in Myket for each timed plan; map the same SKU to **SUBSCRIPTION** in Inappify and set its package duration. Keep lifetime SKUs non-consumable in both catalogs. Ordinary consumables keep their existing host delivery/confirmation flow.

The SDK buys the timed plan as `IN_APP`, preserves `SUBSCRIPTION` in its bound developer payload, and sends the original signed receipt to Inappify. The backend verifies it with Myket, grants/extends access once per purchase token, and returns `requiresConsumption: true` with the committed payment/event IDs. Only then does the SDK consume it. There is no host consumable callback for timed subscriptions. Each manual renewal is a new purchase/token; there is no automatic charge, `subs` inventory, or Bazaar-style subscription chain.

Deploy the matching V2 commerce backend **before** distributing these SDKs: initial/replayed purchase and verification-status responses must include this authorization. Missing/false authorization retains the paid receipt and fails without consuming it. Consumption failures retain the encrypted journal and retry the same receipt; successful consumption is checkpointed before finalization. A Myket ITEM_NOT_OWNED response after a verified grant safely finishes an uncertain prior consume. It does not authorize a new entitlement. No journal schema or public method changes are required. Older SDKs still reject initiating Myket subscriptions; downgrading before pending Myket subscription work finishes is not supported.

Recovery examines bound Myket `IN_APP` receipts, including subscriptions already present in CustomerInfo, to finish consumption after process death. Explicit restore can reconcile an unconsumed bound subscription receipt; consumed history is obtained from server CustomerInfo for the same identity, not Myket inventory. Receipts without a valid customer/package binding are not adopted. Bazaar subscription, Direct checkout and V1 service behavior are unchanged.

Release acceptance on a real Myket device: purchase each of the three product types; confirm lifetime remains owned, consumables wait for host delivery, and timed plans grant once then become buyable again. Repeat renewal, cancellation, network loss during verification/consumption, process death and restore. Automated mocks do not establish real-store acceptance.
