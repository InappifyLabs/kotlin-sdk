# Kotlin API reference

Artifact: `com.github.InappifyLabs:kotlin-sdk:v0.2.0`.
SDK-reported version: `0.2.0`, without the
Git tag prefix. Configure, snapshots and targeting use that exact value; local
version normalization does not alter production server offering selection. Public types and extension functions live in
`com.inappify.sdk`; import individual symbols or `com.inappify.sdk.*`.
This reference describes the production client returned by the public factories.

## Client factories and ownership

`InappifyClient.create(context: Context): InappifyV2Client` and
`InappifyV2Client.create(context: Context): InappifyV2Client` create the same
production session owner, use `context.applicationContext`, bundled signing trust,
and application-private encrypted persistence. Creating the client makes no HTTP
request. Retain one client for the app/DI scope and call `close()` when that scope
is permanently released. A closed client cannot be reused. Closing cancels its
owned operations and adapters; it does not perform logout or erase durable paid
work. Concurrent state mutations serialize; identical supported requests can
share an in-flight operation without sharing ownership of a caller's coroutine.

`InappifyV2Client.create(context, configuration)` accepts explicit trusted service
configuration. It is an advanced deployment option, not required for normal
Inappify integration.

## Configuration and identity

### `InappifyOptions`

| Property | Type/default | Behavior |
| --- | --- | --- |
| `apiKey` | required `String` | App public key for session bootstrap/renewal; nonblank, at most 4096 characters, no controls |
| `appUserIdentifier` | `String? = null` | Current host customer; omission may retain an existing customer, otherwise create an anonymous identity |

Configure accepts only the app API key and optional customer identity. Store routing and the RSA public key come from the server session. Android package metadata supplies versionName/versionCode; country resolution and subscription recovery retain their internal defaults.

`InappifyMarket` values are `BAZAAR`, `MYKET`, and `NONE`. Verified session
`storePlatform` values for supported Android routes are `Bazar`, `MyKet`, and
`DirectAndroid`. Unknown or conflicting route selections fail explicitly.

| Method | Result | Production behavior |
| --- | --- | --- |
| `configure(options)` | `InappifyResult<Unit>` | Verify/load app-bound cache, resolve identity, reuse or establish a verified session, schedule background recovery |
| `logIn(appUserIdentifier: String)` | `InappifyResult<Unit>` | Transition from the current session to a custom customer; HTTP uses the session Bearer |
| `logOut()` | `InappifyResult<Unit>` | Complete the durable logout transition and create an anonymous session |
| `isCustomerAnonymous(appUserIdentifier)` | `Boolean` | Client helper recognizes valid SDK anonymous identifiers |
| `handleDeepLink(url: String)` | `Boolean` | Recognize/queue a marked InAppify native payment return; ignore ordinary/invalid/other-app links; does not report payment success |
| `setNetworkEnabled(enabled)` | `Unit` | Enable/disable SDK network use without deleting cache |

Foreground, restored internet and reenabling SDK networking check for unfinished
current-customer purchases or five minutes since successful recovery. Automatic
recovery waits at least thirty seconds after failure. These client-lifetime
timestamps do not change persisted cache, purchase journals or server requests.

Custom identity is 1–100 Unicode code points, with no controls, edge whitespace,
malformed Unicode or reserved anonymous prefix. Login does not accept an anonymous
identity as its target. `logIn` takes only the customer identifier;
the configured session is authoritative. Changing the app key/service
binding requires a new client. `isLogoutPending` exposes the logout barrier.

Use Configure to load/reuse verified cache; do not assume a network-free Configure.
Standalone `isCustomerAnonymous(String?)` is a broader string helper and can treat
null as anonymous; prefer the client helper for an actual SDK identity.

## Customer state and entitlements

| Method | Result/behavior |
| --- | --- |
| `getCustomerInfo(fetchPolicy = CACHED_OR_FETCHED)` | `InappifyResult<InappifyCustomerInfo>`; see the four CustomerInfo policies below |
| `customerInfo(policy)` | Explicit cache/network policy |
| `isActiveEntitlement(identifier)` | Synchronous Boolean from latest snapshot |
| `hasEntitlement(identifier)` | Alias of `isActiveEntitlement` |
| `getEntitlement(identifier)` | Active `InappifyEntitlement?` from latest snapshot |

Production entitlement checks require `isActive == true`. A supplied nonblank
expiration must parse and lie in the future according to the client's trusted
time; expired/malformed values do not authorize access. These helpers do not grant
consumable inventory. Check `entitlements.active` on a verified `getCustomerInfo`
result, or use the synchronous client helpers for the latest snapshot. To wait for
the server first, use `getCustomerInfo(InappifyCacheFetchPolicy.FETCH_CURRENT)`.

`InappifyCustomerInfo` fields: `originalAppUserId`, `firstSeen`, `requestDate`,
`latestExpirationDate`, `hasUsedTrial`, `entitlements`, `transactions`, `attributes`.
`entitlements` is a non-null `InappifyEntitlementInfos`: `all` contains known active,
expired and inactive rights; `active` filters rights using the trusted clock at
access time; `[identifier]` looks up any known right. Both maps are read-only.
Existing list operations remain supported. Transactions, attributes and optional
server values may be null. An entitlement contains
`identifier`, `isActive`, `isSandbox`, `periodType`, `purchaseDate`, `expirationDate`,
`ownershipType`, `entitlementType`, `purchaseStoreRefHash`, `purchaseStoreTime`.
Store time is epoch milliseconds, not seconds. `InappifyTransaction` summarizes
number/status/amount/date/tracking, crypto/trial flags, package and store reference;
it is not a purchase token. `InappifyAttribute` contains nullable `key` and `value`.

### Fetch policy

`getCustomerInfo` uses `InappifyCacheFetchPolicy`:

| Value | Behavior |
| --- | --- |
| `CACHE_ONLY` | Return verified cache or CACHE_MISS; never fetch |
| `FETCH_CURRENT` | Wait for the server and return any failure without a stale fallback |
| `NOT_STALE_CACHED_OR_CURRENT` | Return fresh cache, otherwise wait for the server; no stale fallback on error |
| `CACHED_OR_FETCHED` (default) | Return available cache immediately, refresh if stale; wait for the server when cache is absent |

CustomerInfo becomes stale after five minutes in the foreground or twenty-five
hours in the background. Reads, foreground transitions and restored connectivity
can trigger refresh; there is no polling timer. The existing persisted
`infoFetchedAt` timestamp is reused across restarts. Requests share the existing
customer flight and signature/session validation. Failed refreshes retain the
verified cache. Purchase and recovery paths still force their required refreshes.

```kotlin
when (val result = client.getCustomerInfo()) {
    is InappifyResult.Success -> {
        val activePro = result.data.entitlements.active["pro"]
        val knownPro = result.data.entitlements["pro"] // may be inactive
    }
    is InappifyResult.Failure -> { /* Show/retry the error when no cache exists. */ }
}
val current = client.getCustomerInfo(InappifyCacheFetchPolicy.FETCH_CURRENT)
```

Go V2 returns one selected record per entitlement identifier, including inactive
history. Active grants take priority over expired/revoked records. Its wire
`entitlements` remains an array inside signed CustomerInfo; existing disk cache
formats do not change. V1 remains active-only.

`InappifyFetchPolicy` applies to `customerInfo` and `offerings`:

| Value | Behavior |
| --- | --- |
| `CACHE_ONLY` | Return usable current cache or a CACHE_MISS failure; no resource HTTP |
| `CACHE_FIRST` | Return cache immediately; CustomerInfo schedules its existing refresh, Offerings uses foreground/freshness checks; otherwise request network |
| `NETWORK_FIRST` | Request network and fall back to the retained resource if the request fails and cache remains available |
| `NETWORK_ONLY` | Request network and surface failure without a resource-cache fallback |

Cache belongs to the configured app/customer/targeting context. A cached success
does not prove a new payment finished. Offerings have a five-minute automatic
refresh threshold and thirty-second failure backoff, both held only in runtime
memory. Automatic prefetch/refresh starts only while the app is foreground;
cached data returns immediately. A disk-restored catalog is usable immediately
and gets one foreground validation. The advanced `customerInfo(policy)` method
retains its previous behavior; new integrations should use `getCustomerInfo`.
Explicit network policies and admitted targeting-sync calls bypass this automatic
schedule. Inspect snapshots/events for later refresh.

## Offerings and targeting

| Method | Result/behavior |
| --- | --- |
| `getOfferings()` | `InappifyResult<InappifyOfferings>` using CACHE_FIRST |
| `getOffering(identifier)` | `InappifyResult<InappifyOffering?>`; exact identifier via the normal cache/fetch flow; missing ID is a successful null |
| `offerings(policy)` | Explicit cache policy |
| `getCurrentOffering(placementIdentifier = null)` | `InappifyResult<InappifyOffering?>`; normal cache/fetch flow, then placement → server current → catalog default |
| `syncAttributesAndOfferingsIfNeeded()` | Upload queued attributes and fetch fresh offerings, subject to the per-client limiter |

For manual refresh, await `syncAttributesAndOfferingsIfNeeded()`, then select
from its returned collection or call `getCurrentOffering(placementIdentifier)`.
`getCurrentOffering` follows `getOfferings()` caching and has no `forceRefresh`
parameter. Automatic refresh retains its independent freshness and retry policy.

The production client does not evaluate downloaded targeting rules or expose a
local context override for the server's decision. Offerings requests
send appVersion, SDK version, country and supported paywall schema/renderer
versions using the current verified session. Country and app version are supplied
automatically by the SDK; there is no public metadata override. Requests flush
queued attributes first.
`InappifyOfferingEvaluationContext(country, platform, appVersion, sdkVersion = "",
appId = null, customAttributes = emptyMap())` is available for model-level helpers;
creating it does not change the production session.

Targeting sync permits five admitted calls per rolling 60 seconds using a
monotonic clock. Failed/cancelled admitted calls count. Excess calls make no HTTP
and return current-session cached offerings, or `UNKNOWN` with details
`reason = RATE_LIMITED_NO_CACHE` and `retryAfterMillis` if no cache exists. Login
and logout do not reset the limit; other APIs are not governed by this local limit.

`InappifyOfferings` exposes `offerings`, `rules`, `fetchedAt`,
`currentOfferingIdentifier`, `placements`, and `usesServerTargeting`.
It also exposes read-only `all: Map<String, InappifyOffering>` and `current`,
exact `getOffering(identifier)` / `[identifier]` lookups, and
`getCurrentOfferingForPlacement(placementId)`. These navigate the existing
collection without network requests or cache writes. Current selects the server's
identifier then the catalog default; placement selection falls back to Current,
including unknown and explicit null placement mappings. An unknown Offering ID
returns null instead of substituting Current. This preserves the Go contract.
Production resources preserve server targeting and omit rule/force-update inputs.
`defaultOffering()` returns the first default and can throw when a non-null
collection has no default; `getCurrentOffering()` safely supports null selection.
`currentOffering(context, placement)`/`resolveOffering` follow server selection
for server-targeted models. `evaluateField`, `evaluateOperator` and
`convertVersionToDouble` are model helpers, not replacements for server targeting.

Model navigation:

- `InappifyOffering`: nullable identifier, default flag, description, trialDays,
  opaque metadata, packages and opaque paywall document. `availablePackages` is
  a non-null read-only view of `packages`, empty when packages are absent.
- `InappifyPackage`: nullable identifier, packageType, description, entitlement,
  name, discountPercent, product and reconstructed parent `offeringIdentifier`.
- `InappifyStoreProduct`: nullable identifier/name, prices, dollarPrice, trialDays
  and server-supplied `productType`.
- `InappifyPrice`: currency and monetary/catalog fields; `price`, `originPrice`
  and IDs are nullable Long, while amount/originalAmount/discountAmount are Number.
- `InappifyRule`, `InappifyCondition`, `InappifyPlacement` and
  `InappifyPlacementOffering` represent model-level rule/placement structures.

Opaque metadata/paywalls are deeply immutable JSON-compatible values. Product
classification comes from Go V2; missing or unknown values remain null. The
package-based purchase API does not infer classification from package period.

## Attributes and discounts

| Method | Result/behavior |
| --- | --- |
| `setAttributes(attributes: Map<String, String?>)` | Same validation, durable queue and flush; success returns an empty `List<InappifyAttribute>` |
| `setReservedAttribute(InappifyReservedAttributeRequest(attribute, value))` | `InappifyResult<Unit>` through the same queue/validation |
| `canSetReservedAttribute(key)` | `InappifyResult<Boolean>` for an exact supported backend key |
| `getDiscountedPaymentLinks(discountCode: String)` | `InappifyResult<InappifyDiscountCodeResult>`; checks the code and returns eligible web payment links |

Use `setAttributes(mapOf("key" to null))` or an empty-string value to delete
attributes. `setAttributes` owns the durable enqueue-and-flush flow; callers do
not manage a separate queue. Failed network sends retain pending writes for the
existing internal retry flow or `syncAttributesAndOfferingsIfNeeded()`.

Custom keys match `[A-Za-z][A-Za-z0-9_-]{0,39}`. Values are null or at most
500 Unicode code points. Null or the empty string removes a value; whitespace
is a value, not a deletion marker. Invalid input fails the call instead of being
silently dropped. The durable queue allows at most 1000 distinct keys; uploads
use batches of up to 50. A definitive HTTP 422 quarantines the rejected batch.
Attributes are write-only through these APIs; do not expect them to appear in
CustomerInfo or a setter's returned list.

The server contract remains `POST /app/v2/attributes` with session Bearer
authentication and JSON `{ "attributes": { "key": "value", "removed": null } }`.
Each upload contains 1–50 entries; the server returns HTTP 204 on success and
enforces a maximum of 50 custom attributes per customer.

Supported reserved backend keys are `$email`, `$displayName`, `$apnsTokens`,
`$fcmTokens`, `$idfa`, `$idfv`, `$ip`, `$phoneNumber`, `$campaign`, `$keyword`.
Convenience functions are `setEmail`, `setDisplayName`, `setApnsToken`,
`setFcmToken`, `setIdfa`, `setIdfv`, `setIp`, `setPhoneNumber`, `setCampaign`, `setKeyword`.
`setIp(value)` writes the supplied IP attribute through the normal queue; it does
not collect or download an IP. An empty string clears it. Query capability with
the exact backend key. Use `setDisplayName` for the display-name attribute.

`getDiscountedPaymentLinks("SAVE20")` takes only the discount code and uses the
current session. The HTTP contract remains `POST /app/v2/validateDiscountCode`
with JSON `{ "code": "SAVE20", "paymentReturnVersion": 1 }` and session Bearer
authentication. The SDK announces the return capability internally; older requests
without that field keep their original checkout/return URLs. No Offering
identifier or request object is needed. SDK success is distinct from `data.isValid`:
invalid codes remain successful business results; V2 database failures return
HTTP 500 / `DISCOUNT_LOOKUP_FAILED` and surface as retryable SDK failures through
the existing retry policy. No raw database details are returned to the app.
The unchanged result contains `errorCode`, code/discount IDs, percent, message
and paymentLinks. Its optional `offering` field remains supported but is not sent
by the current Go endpoint. Each `InappifyPurchaseLink` has an offering identifier
and URL. Payment URLs must pass the configured allowlist. The method does not
persist the code, modify cached prices or redeem it; Laravel revalidates the code
and calculates the price when the returned web link is used. Current purchase APIs
reject `discountCode` and a nonzero `discount`; do not assume code validation makes
those purchase-request fields supported.

## Purchase requests

`purchase(purchaseParams: InappifyPurchaseParams)` returns the existing
`InappifyResult<InappifyPurchase>`. Use a package from an Offering:

```kotlin
client.purchase(InappifyPurchaseParams.Builder(activity, selectedPackage).build())
```

`Builder(selectedPackage)` omits the Activity for Direct checkout or an existing
paid store attempt. A new native purchase still needs a foreground Activity.
The optional Builder methods are `idempotencyKey(String)`
and `paywallAttribution(id: Long, revision: Long)`. Paywall attribution
retains its Direct-only validation. Built parameters are immutable and are not
stored in the client or persisted; do not retain Activity-backed parameters
beyond that Activity's lifecycle.

The adapter reads `package.identifier`, `package.product.identifier`,
`package.offeringIdentifier` and `package.product.productType`. Parent context
is reconstructed from the containing Offering, including after cache reads, and
is not written into the package JSON. Go V2's existing `productType` field is
preserved when present; old catalogs without it keep their existing wire shape.
The cache version, identity scope and durable purchase journal are unchanged.
Incomplete or unbound packages fail with `PURCHASE_PACKAGE_CONTEXT_REQUIRED`.
Native packages without a recognized type fail with `PRODUCT_TYPE_REQUIRED`;
refresh offerings and ensure the product is classified in the catalog. Direct
checkout continues to accept catalogs without product type. None of these
validation failures start billing or issue a purchase request.

`purchase(InappifyPurchaseParams)` returns `InappifyResult<InappifyPurchase>`.
A new store purchase requires a foreground Activity in its Builder; Direct and
resuming an existing paid attempt do not reopen store UI. Bazaar and Myket require
AndroidX ActivityResultRegistryOwner/LifecycleOwner. Public purchase results omit
original receipts, signatures and purchase/session tokens. Raw purchase commands
are internal and are not an application API.

Purchase waits for active recovery and uses its matching paid outcome, including
after a completed checkpoint was removed. Otherwise it resumes a known unfinished
purchase/delivery for this product and current customer/app/store/endpoint before
creating fresh work. Original paid Offering/Package bindings and attempt-ID
conflicts remain authoritative. Unresolved work returns the existing typed
status/failure; unrelated info/catalog recovery errors do not block an otherwise
valid purchase. A customer change while waiting returns `PURCHASE_CONTEXT_CHANGED`.
These checks add no parameters or disk fields and do not start a full recovery
for each purchase. See [Recovery and cache](RECOVERY_AND_CACHE.md#before-a-new-purchase).

Direct creation is not blindly retried. Its local `idempotencyKey` correlates the
result but is not sent as a server idempotency header by the current checkout
endpoint. An uncertain creation can have committed; recover customer/delivery
state before deliberately creating another checkout.

## Purchase and delivery results

`InappifyPurchase` fields include attemptId, product/offering/package identifiers,
market, `purchaseStatus`, URL, optional checkout/next-action metadata,
`deliveryId`, `verificationRequestId`, `alreadyProcessed` and `storePurchaseStatus`.
Nullable checkout fields are not a public checkout-status polling API.
`InappifyPurchaseStatus` is `DONE` or `NEEDTOPAY`.

`InappifyStorePurchaseStatus` values:

| Status | Meaning |
| --- | --- |
| `PROCESSING` | Pending server verification; resume/poll is SDK-owned |
| `COMPLETED` | Server confirmed the item |
| `RESTORED` | Server restored an owned item |
| `ALREADY_PROCESSED` | Duplicate event already registered |
| `DELIVERY_REQUIRED` | Verified consumable awaits durable host grant |
| `CONSUME_REQUIRED` | Grant acknowledged; native store consumption still needed |
| `REJECTED` | Definitively rejected evidence; never grant |

Store terminal success statuses map to `purchaseStatus = DONE`; pending/delivery/
consume/rejected states do not. The enum represents backend workflow states:
PROCESSING/backoff and unfinished consumption can return Failure with durable
work retained. The production purchase path returns Success for terminal results
or a pending delivery, not for every enum state. Access comes from verified signed CustomerInfo,
not this mapping alone.

| Extension/method | Result/behavior |
| --- | --- |
| `setConsumableDeliveryHandler(handler)` | Registration; register before Configure; callback runs off main thread |
| `syncPendingConsumables()` | `InappifyResult<InappifyConsumableSyncResult>`; discover/resume deliveries and invoke registered handler |
| `confirmDelivery(deliveryId: Long)` | `InappifyResult<InappifyPurchase>`; acknowledge only a durable host grant; consume store item afterward |
| `syncStorePurchases()` | `InappifyResult<List<InappifyPurchase>>`; reconcile owned store work or Direct deliveries |
| `restoreStorePurchases()` | `InappifyResult<InappifyRestoreResult>`; explicit independent-item restore |

`InappifyConsumableDelivery` contains a positive deliveryId, productIdentifier,
optional transactionIdentifier, and source `DIRECT`, `BAZAAR` or `MYKET`.
The host uses deliveryId as a unique durable grant key scoped to the current app/
customer. `InappifyConsumableDeliveryHandler.deliver` returns `DELIVERED` only after
commit, or `RETRY_LATER` to preserve pending work. The SDK never invents a quantity.
`InappifyConsumableSyncResult` exposes discoveredCount, completedCount and
pendingDeliveries. `InappifyRestoreResult` exposes restoredCount,
alreadyProcessedCount, failedCount and totalCount; successful aggregate results
can still contain per-item failures.

## Events, state and diagnostics

`client.snapshot` is an immutable, token-free `InappifySnapshot`: revision,
isConfigured, isAuthenticated, appUserIdentifier, market, country, appVersion,
sdkVersion, storeInfo, appId, customerInfo, offerings,
failedToLoadCustomerInfo, failedToLoadOfferings and storePlatform.
`forceVersion` and `hasForceUpdate` are retired and are not public state. Old
cache/journal fields remain readable for migration and do not enforce updates.

`addEventListener(InappifyEventListener)` returns an
`InappifyListenerRegistration`. Close it when observation ends; close is
idempotent and `isClosed` reports its state. `InappifyEvent` carries type, complete
snapshot and optional sanitized requestId. Types are STATE_CHANGED,
CUSTOMER_INFO_CHANGED, OFFERINGS_CHANGED, AUTHENTICATION_CHANGED and
PURCHASE_UPDATED. The production client currently emits the first four categories;
PURCHASE_UPDATED is an available enum value, not a completion event to wait for.
Production events dispatch on the main thread; listener errors
are isolated. `stateUpdates(): Flow<InappifySnapshot>` emits current state and
STATE_CHANGED snapshots, distinct by revision, and removes its listener when
collection stops.

`verifiedSessionScope` is an optional extension property exposing issuer/appId/
projectId only after verified configuration, and null during pending identity
transitions. `isPaymentUrlAllowed(url)` checks the configured exact payment hosts.
Use it before opening a Direct URL; the production host is pay.inappify.com.

`addHttpTraceListener(InappifyHttpTraceListener)` returns a removable registration
for bounded, filtered HTTP diagnostics. `InappifyHttpTrace` provides method,
endpoint, filtered request/response headers and bodies, statusCode, requestId,
durationMillis, failure and containsSensitiveData. The public factory does not
provide a raw-secret mode. The callback may run on a background thread and should
return promptly. `InappifyHttpFailure` values are NETWORK, TIMEOUT, CANCELLED,
MALFORMED_RESPONSE. Keep credentials, identities, receipts and checkout URLs out
of application logs even when collecting SDK diagnostics.

## Paywall helpers

The SDK's native renderer is a package-list fallback, not a general-purpose
remote element renderer. Use your own UI or
`InappifyPaywall.createPackageView(context, offering, typography =
InappifyPaywallTypography(), locale = Locale.getDefault(), darkMode = false,
onPurchase)` on the main thread. Its callback gives a package from that exact
offering; the host calls the normal purchase API and handles fulfillment.

`InappifyPaywallTypography(regular, medium, bold)` accepts app-bundled font resource
IDs, not remote fonts. `resourceForWeight` selects the nearest supplied weight.
`InappifyPaywallIcons.normalize` accepts supported semantic icon IDs.
`InappifyPaywallPolicy.isCompatible(document, schemaVersion, rendererVersion)`
checks positive IDs/revisions, compatible versions and structural limits; it does
not certify an arbitrary element grammar. `sanitizeAsset(asset, assetHosts)`
filters rendering fields and validates remote image URLs against exact hosts;
`resolvePackage(offering, packageId)` restricts a UI action to a package in that
offering. Advertised native paywall schema/renderer versions are currently 1.

## Advanced service configuration

`InappifyV2Configuration` accepts HTTPS service roots ending exactly in `/app/v2/`,
trusted issuer, positive appId/projectId, nonempty `pinnedSigningKeys`, exact
lowercase paymentHosts and optional assetHosts. Keys map `kid` to unpadded
base64url raw 32-byte Ed25519 public keys. Obtain trust anchors from your operator
through a trusted channel; a download alone does not establish signing trust.

`sdkApiBaseUrl`/`apiBaseUrl` configure session resources. The constructor with
`commerceApiBaseUrl` supports a separate commerce service. Defaults are
`https://service.inappify.com/app/v2/` and `https://api.inappify.com/app/v2/`.
Session scope is verified, not caller-selected in a request. Payment hosts are
exact names without wildcards; URL checks require HTTPS, port443 and no URL
credentials. Normal Configure needs only the app public key and store setup;
applications do not manage session Bearer tokens themselves.

## Errors and cancellation

`InappifyResult.Success<T>` contains data and an authoritative snapshot.
`InappifyResult.Failure` contains `InappifyError` and the latest snapshot when
available. `InappifyError` has code, sanitized message, isRetryable and details.
Do not treat Success<Unit> or a cached snapshot as a new transaction receipt.

| Error category | Typical host handling |
| --- | --- |
| `INVALID_CONFIGURATION`, `NOT_CONFIGURED` | Correct setup/current identity; Configure before purchase |
| `UNAUTHORIZED` | Reconcile/restore the appropriate current session; do not inject another Bearer |
| `NETWORK`, `TIMEOUT` | Keep pending state and retry recovery when connectivity returns |
| `REQUEST_CANCELLED`, `PURCHASE_CANCELLED` | End immediate UI while respecting any durable/uncertain payment state |
| `STORE_UNAVAILABLE` | Resolve store installation/login/key/foreground host setup |
| `PURCHASE_IN_PROGRESS` | Keep one active host purchase workflow |
| `PURCHASE_VERIFICATION_FAILED` | No grant; inspect sanitized reason and reconcile according to retryability |
| `UNSUPPORTED_OPERATION` | Use an API/route supported by the production client |
| `MALFORMED_RESPONSE`, `UNKNOWN` | No speculative grant; retain uncertainty and use diagnostics/recovery |

Inspect details such as category, reason, httpStatus, serverCode,
retryAfterMillis and outcomeMayHaveCommitted when supplied. The SDK renews a
commerce session once only for HTTP401 SESSION_EXPIRED, persists a verified
replacement, and replays with bounded behavior; a generic401 is not blanket
permission to retry a payment. A payloadless subscription's definitive ownership/
evidence rejection quarantines its checkpoint; transient failures preserve it.
Coroutine cancellation can throw CancellationException, and calls after close
are invalid lifecycle use. Do not convert either into payment success.

## Logging

| Global setting | Purpose |
| --- | --- |
| `InappifyClient.logLevel = InappifyLogLevel.DEBUG` | Minimum severity; set before Configure |
| `InappifyClient.logHandler = handler` | Replace the default Logcat output |
| `InappifyClient.logHandler = DefaultInappifyLogHandler()` | Restore Logcat output |

Levels: VERBOSE, DEBUG, INFO, WARN, ERROR. Defaults: DEBUG in development,
INFO in release. Configuration is process-wide and does not require a session.
See [safe logging and handler examples](SECURITY_AND_DIAGNOSTICS.md#sdk-log-levels-and-custom-handling).
