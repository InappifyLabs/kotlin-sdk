# Kotlin API reference

Artifact: `com.github.InappifyLabs:kotlin-sdk:v0.1.0`.
SDK-reported version: `0.1.0`, without the
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
| `market` | `InappifyMarket? = null` | Store/key context; cannot replace the server-selected purchase route |
| `marketKey` | `String? = null` | App public RSA key for the selected store; storeInfo in the verified session can supply it when absent |
| `country` | `String? = null` | Two-letter targeting country, normalized uppercase; retained value or IR supplies the production default |
| `appVersion` | `String? = null` | Targeting version; default Android versionName; nonblank and at most 50 characters |
| `enableSubscriptionRecoveryWithoutPayload` | `Boolean = true` | Bazaar-only empty-payload subscription workflow; constructor option with private setter |

The constructor with the Boolean parameter supports explicit opt-out. False
prevents discovery/submission and polling for those pending recovery operations,
while preserving stored evidence. It does not relax binding checks or disable
ordinary bound store recovery. See [recovery](GETTING_STARTED.md#restore-and-automatic-recovery).

`InappifyMarket` values are `BAZAAR`, `MYKET`, and `NONE`. Verified session
`storePlatform` values for supported Android routes are `Bazar`, `MyKet`, and
`DirectAndroid`. Unknown or conflicting route selections fail explicitly.

| Method | Result | Production behavior |
| --- | --- | --- |
| `configure(options)` | `InappifyResult<Unit>` | Verify/load app-bound cache, resolve identity, reuse or establish a verified session, schedule background recovery |
| `login(InappifyLoginRequest(apiKey, appUserIdentifier))` | `InappifyResult<Unit>` | Transition from the current session to a custom customer; HTTP uses the session Bearer |
| `logout()` | `InappifyResult<Unit>` | Complete the durable logout transition and create an anonymous session |
| `isCustomerAnonymous(appUserIdentifier)` | `Boolean` | Client helper recognizes valid SDK anonymous identifiers |
| `recover()` | `InappifyResult<Unit>` | Reconcile pending identity transition, customer state, store/delivery work and offerings |
| `setNetworkEnabled(enabled)` | `Unit` | Enable/disable SDK network use without deleting cache |

Custom identity is 1–100 Unicode code points, with no controls, edge whitespace,
malformed Unicode or reserved anonymous prefix. Login does not accept an anonymous
identity as its target. Use the same configured app public key in
`InappifyLoginRequest`; the session is authoritative. Changing the app key/service
binding requires a new client. `isLogoutPending` exposes the logout barrier.

The `restoreCachedSession(options): InappifyResult<Boolean>` extension is exported
but returns `UNSUPPORTED_OPERATION` for the production factory client. Use
Configure to load/reuse its verified cache; do not assume a network-free Configure.
Standalone `isCustomerAnonymous(String?)` is a broader string helper and can treat
null as anonymous; prefer the client helper for an actual SDK identity.

## Customer state and entitlements

| Method | Result/behavior |
| --- | --- |
| `getCustomerInfo(forceRefresh = true)` | `InappifyResult<InappifyCustomerInfo>`; true uses NETWORK_ONLY, false uses CACHE_FIRST |
| `refreshCustomerInfo()` | Same result, NETWORK_ONLY |
| `customerInfo(policy)` | Explicit cache/network policy |
| `checkEntitlement(identifier, forceRefresh = true)` | `InappifyResult<Boolean>`; refreshes customer state by default |
| `isActiveEntitlement(identifier)` | Synchronous Boolean from latest snapshot |
| `hasEntitlement(identifier)` | Alias of `isActiveEntitlement` |
| `getEntitlement(identifier)` | Active `InappifyEntitlement?` from latest snapshot |

Production entitlement checks require `isActive == true`. A supplied nonblank
expiration must parse and lie in the future according to the client's trusted
time; expired/malformed values do not authorize access. These helpers do not grant
consumable inventory. CustomerInfo extension helpers also exist for operating on
a model directly; the client methods are the authoritative integration path.

`InappifyCustomerInfo` fields: `originalAppUserId`, `firstSeen`, `requestDate`,
`latestExpirationDate`, `hasUsedTrial`, `entitlements`, `transactions`, `attributes`.
Collections and optional server values may be null. An entitlement contains
`identifier`, `isActive`, `isSandbox`, `periodType`, `purchaseDate`, `expirationDate`,
`ownershipType`, `entitlementType`, `purchaseStoreRefHash`, `purchaseStoreTime`.
Store time is epoch milliseconds, not seconds. `InappifyTransaction` summarizes
number/status/amount/date/tracking, crypto/trial flags, package and store reference;
it is not a purchase token. `InappifyAttribute` contains nullable `key` and `value`.

### Fetch policy

`InappifyFetchPolicy` applies to `customerInfo` and `offerings`:

| Value | Behavior |
| --- | --- |
| `CACHE_ONLY` | Return usable current cache or a CACHE_MISS failure; no resource HTTP |
| `CACHE_FIRST` | Return cache immediately when present; schedule background refresh; otherwise request network |
| `NETWORK_FIRST` | Request network and fall back to the retained resource if the request fails and cache remains available |
| `NETWORK_ONLY` | Request network and surface failure without a resource-cache fallback |

Cache belongs to the configured app/customer/targeting context. A cached success
does not prove a new payment finished, and no fixed five-minute freshness promise
is imposed by these production getters. Inspect snapshots/events for later refresh.

## Offerings and targeting

| Method | Result/behavior |
| --- | --- |
| `getOfferings()` | `InappifyResult<InappifyOfferings>` using CACHE_FIRST |
| `refreshOfferings()` | Same result using NETWORK_ONLY |
| `offerings(policy)` | Explicit cache policy |
| `getCurrentOffering(placementIdentifier = null, forceRefresh = false, context = null)` | `InappifyResult<InappifyOffering?>`; placement → server current → catalog default |
| `setTargetingContext(country = null, appVersion = null)` | `InappifyResult<Unit>`; change non-null fields and invalidate offerings |
| `syncAttributesAndOfferingsIfNeeded()` | Upload queued attributes and fetch fresh offerings, subject to the per-client limiter |

The production client does not evaluate downloaded targeting rules or use the
optional `context` parameter to override the server's decision. Offerings requests
send appVersion, SDK version, country and supported paywall schema/renderer
versions using the current verified session. They flush queued attributes first.
`InappifyOfferingEvaluationContext(country, platform, appVersion, sdkVersion = "",
appId = null, customAttributes = emptyMap())` is available for model-level helpers;
creating it does not change the production session.

Targeting sync permits five admitted calls per rolling 60 seconds using a
monotonic clock. Failed/cancelled admitted calls count. Excess calls make no HTTP
and return current-session cached offerings, or `UNKNOWN` with details
`reason = RATE_LIMITED_NO_CACHE` and `retryAfterMillis` if no cache exists. Login
and logout do not reset the limit; other APIs are not governed by this local limit.

`InappifyOfferings` exposes `offerings`, `rules`, `forceVersion`, `fetchedAt`,
`currentOfferingIdentifier`, `placements`, and `usesServerTargeting`.
Production resources preserve server targeting and omit rule/force-update inputs.
`defaultOffering()` returns the first default and can throw when a non-null
collection has no default; `getCurrentOffering()` safely supports null selection.
`currentOffering(context, placement)`/`resolveOffering` follow server selection
for server-targeted models. `evaluateField`, `evaluateOperator` and
`convertVersionToDouble` are model helpers, not replacements for server targeting.

Model navigation:

- `InappifyOffering`: nullable identifier, default flag, description, trialDays,
  opaque metadata, packages and opaque paywall document.
- `InappifyPackage`: nullable identifier, packageType, description, entitlement,
  name, discountPercent and product.
- `InappifyStoreProduct`: nullable identifier/name, prices, dollarPrice and trialDays.
- `InappifyPrice`: currency and monetary/catalog fields; `price`, `originPrice`
  and IDs are nullable Long, while amount/originalAmount/discountAmount are Number.
- `InappifyRule`, `InappifyCondition`, `InappifyPlacement` and
  `InappifyPlacementOffering` represent model-level rule/placement structures.

Opaque metadata/paywalls are deeply immutable JSON-compatible values. Catalog
models do not expose a public product-type classification; the host supplies the
correct configured SKU type when purchasing.

## Attributes and discounts

| Method | Result/behavior |
| --- | --- |
| `queueAttributes(values: Map<String, String?>)` | `InappifyResult<Unit>`; durably enqueue write-only changes and invalidate offerings |
| `flushAttributes()` | `InappifyResult<Unit>`; upload queued batches |
| `setAttributes(InappifyAttributesRequest(attributes, idempotencyKey = null))` | Queue then flush; success returns an empty `List<InappifyAttribute>` |
| `updateAttributes(values)` | Map convenience for setAttributes |
| `deleteAttributes(InappifyDeleteAttributesRequest(keys))` | Enqueue removals and flush |
| `syncAttributes(request = null)` | Flush existing queue; with a request, queue then flush; no attribute download |
| `setReservedAttribute(InappifyReservedAttributeRequest(attribute, value))` | `InappifyResult<Unit>` through the same queue/validation |
| `canSetReservedAttribute(key)` | `InappifyResult<Boolean>` for an exact supported backend key |
| `validateDiscountCode(InappifyDiscountCodeRequest(discountCode, offeringIdentifier = null))` | `InappifyResult<InappifyDiscountCodeResult>` |

Custom keys match `[A-Za-z][A-Za-z0-9_-]{0,39}`. Values are null or at most
500 Unicode code points. Null or the empty string removes a value; whitespace
is a value, not a deletion marker. Invalid input fails the call instead of being
silently dropped. The durable queue allows at most 1000 distinct keys; uploads
use batches of up to 50. A definitive HTTP 422 quarantines the rejected batch.
Attributes are write-only through these APIs; do not expect them to appear in
CustomerInfo or a setter's returned list. `idempotencyKey` in the attribute request
is not sent by the current endpoint.

Supported reserved backend keys are `$email`, `$displayName`, `$apnsTokens`,
`$fcmTokens`, `$idfa`, `$idfv`, `$phoneNumber`, `$campaign`, `$keyword`.
Convenience functions are `setEmail`, `setDisplayName`, `setApnsToken`,
`setFcmToken`, `setIdfa`, `setIdfv`, `setPhoneNumber`, `setCampaign`, `setKeyword`.
The enum also exposes `IP` and the client exposes `setIp`, but `$ip` is not in the
production supported-key set and its setter fails validation. Query capability
with the exact backend key. `setDisplayname` is a deprecated alias; use
`setDisplayName`.

Discount validation sends only `code` in the current session; its
`offeringIdentifier` constructor field is not sent. SDK success is distinct from
`data.isValid`. The result can contain `errorCode`, code/discount IDs, percent,
message, paymentLinks and an offering. Each `InappifyPurchaseLink` has offering
and URL. Payment URLs must pass the configured allowlist. Current purchase APIs
reject `discountCode` and a nonzero `discount`; do not assume code validation makes
those purchase-request fields supported.

## Purchase requests

Both constructors require product/offering identifiers. Prefer the constructor
with required `productType: InappifyProductType` for a new store integration.
`CONSUMABLE` uses the grant/acknowledge/consume workflow, `NON_CONSUMABLE` represents
a durable one-time purchase, and `SUBSCRIPTION` uses store subscription billing.
Myket rejects SUBSCRIPTION. Omitting productType selects in-app billing without
inferring whether the item is consumable or non-consumable; the backend determines
fulfillment.

| Field | Current supported use |
| --- | --- |
| `productIdentifier`, `offeringIdentifier` | Required; product must belong to a currently loaded offering for a new attempt |
| `packageIdentifier` | Select a matching Inappify package; do not pass Android package name |
| `productType` | Store product classification; required to start subscription billing |
| `idempotencyKey` | Optional `[A-Za-z0-9_.:-]{1,128}`; unique per logical store attempt; same paid retry resumes its journal |
| `isCrypto` | Direct only, default false |
| `paywallId`, `paywallRevision` | Direct attribution via `withPaywallAttribution(id, revision)`; both positive |
| `market` | Default NONE; stores can accept their matching route; cannot switch server route |
| `apiKey`, `marketKey`, `dynamicPriceToken` | Unsupported request overrides; omit |
| `country`, `appVersion`, `discountCode`, `discount` | Unsupported request overrides; omit/default zero |
| `isLostPurchase`, `lostPurchaseToken`, `lostPurchaseTime` | Not a receipt-import API; leave unused and call recovery instead |

`purchase(request)` and `purchase(activity, request)` return
`InappifyResult<InappifyPurchase>`. A new store purchase requires a foreground
Activity; Direct and resuming an existing paid attempt do not reopen store UI.
The Activity overload supports the native store UI; both Bazaar and Myket require
AndroidX ActivityResultRegistryOwner/LifecycleOwner. Public purchase results deliberately
omit original receipts, signatures and purchase/session tokens.

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
| `syncPurchases()` | `InappifyResult<List<InappifyPurchase>>`; reconcile owned store work or Direct deliveries |
| `restorePurchases()` | `InappifyResult<InappifyRestoreResult>`; explicit independent-item restore |

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
sdkVersion, storeInfo, forceVersion, appId, customerInfo, offerings,
failedToLoadCustomerInfo, failedToLoadOfferings and storePlatform. The production
client's `hasForceUpdate` is false and forceVersion is not a rollout-enforcement API.

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
