## 0.2.3

- Use the committed server product type for Myket recovery, including receipts created by older SDKs with a different local type. Require matching purchase scope and committed payment/delivery evidence before changing recovery behavior.
- Finish consumption of previously granted Myket subscriptions without extending access again; preserve durable consumable delivery and never consume non-consumable purchases. Keep Bazaar and Direct behavior unchanged.
- Retain Myket receipts/checkpoints until reconciliation completes and expose the safe numeric Myket callback response code for failed purchases. Deploy the matching commerce backend before upgrading clients.

## 0.2.2

- Support manually renewed Myket timed subscriptions using IN_APP purchases and server-authorized consumption after entitlement grant. Preserve consumable host delivery, lifetime ownership, Bazaar subscriptions and Direct behavior.
- Recover bound subscription receipts from Myket in-app inventory and checkpoint consumption for retries/process death without duplicate grants. Require the matching V2 commerce backend; native/automatic Myket subscriptions remain unavailable.

# Changelog

## 0.2.1

- Correlate new Direct checkout/recovery with the original attempt ID, including
  payment-status lookup after restart. Keep legacy consumable sync/confirm results.
- Add encrypted app/customer/market-bound purchase outcome history with
  `getPurchaseAttempt` / `getPurchaseAttempts` and correlated `PURCHASE_UPDATED`
  events. Save completed store outcomes before checkpoint cleanup for restart-safe
  lookup. Preserve purchase/sync results, cache/journal schemas and scheduling.

## 0.2.0

- Add global RevenueCat-style log levels and custom log handlers, available before
  Configure. Default to DEBUG during development and INFO in release. Preserve
  filtered HTTP traces and keep secrets/bodies out of standard logs at all levels.
  Logging failures do not alter purchase results, cache, retries or recovery.

- Rename public purchase reconciliation to `syncStorePurchases()` and
  `restoreStorePurchases()` without compatibility aliases. Remove public
  `recover()` while preserving internal automatic recovery, scheduling, purchase
  behavior, Direct delivery handling and persisted cache/journal formats.

- Remove public `checkEntitlement(identifier, forceRefresh)`; check
  `entitlements.active` on a successful `getCustomerInfo()` result instead. Keep
  `FETCH_CURRENT` for explicit server reads and preserve existing fetch policies,
  purchase/recovery refreshes and cache behavior.
- Allow the reserved `$ip` attribute in both Kotlin and Go V2 so `setIp(value)`
  uses the existing durable attribute pipeline, including deletion. Keep other
  reserved-key validation, purchase and cache behavior unchanged.
- Remove legacy public login/logout aliases, boolean CustomerInfo reads, public
  customer refresh, attribute request/update aliases, misspelled display-name
  setter, raw purchase entry points, unsupported local restore and ignored
  Offering context. Remove unused legacy request types and force-version
  constructors. Keep the purchase command internal and preserve wire formats,
  verified cache, pending journals, synchronization and recovery behavior.
- Remove public `queueAttributes`, `flushAttributes`, `syncAttributes`,
  `deleteAttributes` and `setTargetingContext`. Keep durable enqueue, batching,
  deletion and synchronization behind `setAttributes`; retain SDK-derived country
  and app-version metadata, automatic refresh, purchase logic and cache formats.
- Remove `forceRefresh` from `getCurrentOffering`; use
  `syncAttributesAndOfferingsIfNeeded()` for manual Offering refresh. Keep normal
  cache reads, automatic refresh scheduling, purchase and recovery behavior unchanged.
- Remove retired `forceVersion`/`hasForceUpdate` state from the V2 API, Offering
  serialization and store response/coordinator metadata. Ignore obsolete response
  values while retaining encrypted cache and journal compatibility.
- Remove `isCrypto` from the new package-purchase API and V2 checkout payload.
  Keep legacy purchase validation internal and preserve historical
  transaction `isCryptoGate` fields. Purchase, cache and restore behavior stay unchanged.
- Recognize reserved InAppify native payment-return markers and target app IDs;
  observe cold/AndroidX new intents, expose `handleDeepLink(url)` for custom
  routing, and force fresh shared recovery with duplicate suppression and error
  backoff. Keep ordinary links, host navigation, purchase/fulfillment logic and
  persisted formats unchanged. Advertise payment-return capability per checkout
  and discount-link request; Laravel supplies markers only for matching capable
  native return actions, preserving old SDK URLs and existing payment snapshots.
- Check paid work before purchase: wait for active recovery and return matching
  recovered outcomes, or resume current-customer native checkpoints/known Direct
  deliveries for that product before opening another payment UI. Keep original
  paid Offering/Package bindings, explicit attempt conflicts, delivery grants and
  per-operation retries. Unrelated info/catalog failures do not block a valid
  purchase. Joined outcomes live only for that recovery pass; disk/HTTP formats
  and public result types are unchanged.
- Automatically reconcile on process foreground and restored internet when this
  customer's purchase checkpoints are unfinished or the last successful recovery
  is at least five minutes old. Back off thirty seconds after recovery failure,
  share active work, and release connectivity observation on close. Scheduling
  remains in memory; purchase logic, cache/journal formats and HTTP contracts stay
  unchanged. Explicit recovery retains its forced behavior.
- Coordinate startup/Login and internal recovery requests in one client-owned
  recovery queue. Coalesce calls for the same accepted customer binding; queue
  a pass after identity changes and stop superseded stages before they can use
  the new session. Keep recovery order, result types, Offering refresh policy,
  purchase behavior and persisted cache/journals unchanged.
- Rename discount-code validation to `getDiscountedPaymentLinks(discountCode)`;
  remove the request object and unused Offering argument, retaining the existing
  result and HTTP contract. Go V2 database lookup failures are distinct retryable
  server errors instead of invalid-code business results. Purchase and cache
  behavior is unchanged.
- Add RevenueCat-style CustomerInfo cache fetch policies with CACHED_OR_FETCHED
  as the default, five-minute foreground/twenty-five-hour background freshness,
  and read-only `entitlements.all`, `entitlements.active` and identifier lookup.
  Preserve existing wire/disk encoding, signature checks and forced purchase/
  recovery refreshes. Go V2 now includes inactive rights; Go V1 is unchanged.
- Migration: `InappifyCustomerInfo.entitlements` now returns non-null
  `InappifyEntitlementInfos` with list operations retained. Recompile consumers
  for the new getter type. Explicit boolean getCustomerInfo calls remain supported.
- Prefetch/refresh Offerings automatically only in the foreground when missing
  or at least five minutes old; back off thirty seconds after automatic errors.
  Coalesce concurrent reads and retain the existing disk cache and purchase
  recovery formats. Explicit refresh/sync requests keep their network behavior.
  Observe process visibility through AndroidX Lifecycle Process 2.7.0 and remove
  the observer when the client closes.
- Add `purchase(InappifyPurchaseParams)` with `Builder(activity, packageToPurchase)`
  and an Activity-free Direct/recovery form. Package context and Go V2 product
  classification supply purchase identifiers through the existing request API.
  Preserve Direct/store routing, payment results, receipt verification, cache
  formats and durable delivery/recovery; keep request overloads and model JVM
  constructors available.
- Add identifier and placement navigation: `getOffering`, `current`, `all`,
  indexed Offering lookup and `availablePackages`, preserving the Go-selected
  Current/Default fallback and existing cache formats.
- Remove `refreshOfferings` from the public client interface while keeping
  internal recovery/background refresh unchanged. Explicit targeting sync uses
  `syncAttributesAndOfferingsIfNeeded`, limited to five calls per rolling minute.
- Add `setAttributes(Map<String, String?>)` through the existing attribute
  validation, durable queue, cache invalidation and Go V2 upload contract.
- Add parameterless `logOut()` as the developer-facing logout method, preserving
  the existing durable logout, anonymous session, cache and retry behavior.
- Add `logIn(appUserIdentifier)` as the developer-facing login method. It delegates
  to the existing request-based login without changing session, cache or retries.
- Configure accepts only the app API key and optional customer identifier.
  Store routing and RSA public keys come from the persisted server session;
  Android package metadata supplies the application version. Existing cache
  formats and automatic recovery remain unchanged.

## 0.1.1

- Login requests now need only `appUserIdentifier`; the configured session
  supplies authentication. Keep the former two-argument constructor deprecated
  for existing callers; its app-key argument is unused.
- Set the runtime version to `0.1.1` and the Git/JitPack tag to `v0.1.1`.

## 0.1.0

- Publish the first stable release from the 0.1.0 beta series.
- Set the runtime SDK version to `0.1.0` and the Git/JitPack tag to `v0.1.0`.
- Preserve the existing public API, payment behavior, recovery and storage formats.

## 0.1.0-beta.0

Initial beta of the Inappify Kotlin SDK in this repository.

- Verified V2 sessions, customer information, entitlements and server-selected offerings.
- Direct checkout and native Cafe Bazaar/Myket purchases with durable reconciliation.
- Idempotent consumable delivery, store acknowledgement and recovery checkpoints.
- Bazaar subscription recovery, including eligible receipts with empty developer payload.
- Encrypted session cache, queued customer attributes, lifecycle recovery and filtered HTTP diagnostics.

Beta releases may change their public API. The runtime SDK version is
`0.1.0-beta.0`; the Git tag and JitPack dependency version are `v0.1.0-beta.0`.
