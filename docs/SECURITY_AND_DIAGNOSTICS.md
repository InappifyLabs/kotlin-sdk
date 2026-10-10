# Security and diagnostics

This guide describes the shipped Android client and its public APIs. The SDK owns session credentials, verifies CustomerInfo, and persists recovery checkpoints. Your application owns account selection, access policy, durable consumable grants, and the lifetime of the client.

Use an application-scoped client and close it when that owner is permanently released. `InappifyClient.create(context)` and `InappifyV2Client.create(context)` create the production V2 client without making an HTTP request. Do not reuse a closed client or create a separate client for every screen.

## Service origins and authentication

The default client uses two HTTPS roots:

| Root | Responsibilities | Authentication |
| --- | --- | --- |
| `https://service.inappify.com/app/v2/` | Configure, login/logout, CustomerInfo, offerings, attributes, discount validation | Configure uses the app's public API key as a Bearer value; subsequent session requests use `sessionToken` as Bearer |
| `https://service.inappify.com/app/v2/public-keys` | Fetch signing-key aliases when required by verification | GET without Bearer |
| `https://api.inappify.com/app/v2/` | Direct purchases, consumable delivery, marketplace purchases and verification, subscription recovery | `sessionToken` as Bearer |

For Configure, the JSON body contains `appUserIdentifier`, `identifierValue`, `versionName`, `versionCode`, `sdkVersion`, and `country`. The API key is an authentication-header exception for this bootstrap request and session renewal; it is not added to that JSON body. Commerce requests do not send an API key or a separate access/refresh token. The marketplace receipt token inside `purchase.token` is purchase evidence, distinct from the session Bearer token.

The SDK supplies and renews session credentials internally. Do not construct payment HTTP requests from the public snapshot. The client performs an authenticated replay only after the specific `401 / SESSION_EXPIRED` response; an arbitrary 401 or uncertain purchase response is not permission to initiate another purchase.

The standard transport disables redirects, accepts only routes under `/app/v2/` on the configured origin, and bounds response bodies to 1 MiB. It uses ordinary HTTPS certificate validation. Its Ed25519 signing-key pins, described below, are application-message trust anchors; they are not TLS certificate pins.

`InappifyV2Configuration` permits explicitly configured SDK and commerce roots, authenticated app/project scope, signing keys, and exact payment host names. Base URLs must use HTTPS, contain no URL credentials, query or fragment, and have the exact `/app/v2/` path. Configure these values from trusted deployment configuration. A public app key embedded in an Android application does not replace backend customer authentication or authorize arbitrary purchase ownership.

## Signed CustomerInfo and scope

CustomerInfo is accepted only after checking the `verification.jws` envelope against a bundled or explicitly configured Ed25519 public key. The verifier checks the signature, algorithm, key ID and envelope type, then validates the signed issuer, app ID, project ID, audience, protocol version, internal customer subject, public app-user identity and validity times. The signed CustomerInfo must equal the response's CustomerInfo object.

The default client learns app/project scope from the first verified session. An explicit configuration requires that signed scope to match its configured values. Ordinary refresh and renewal must keep the current signed internal customer subject; a verified login/logout transition may replace it. `verifiedSessionScope` exposes the verified issuer/app/project identifiers without credentials.

Downloading `public-keys` does not establish trust in arbitrary new key material. Downloaded aliases are accepted only when their public-key bytes match an existing configured trust anchor. Do not disable signature checks or copy a key from an untrusted response to make a verification error disappear.

This signing guarantee applies to CustomerInfo, not to every JSON field or every commerce response. Offerings and commerce replies travel over the configured HTTPS service and are validated by their own schemas and workflow guards. Do not treat a checkout URL, native store callback, or `isAuthenticated` snapshot flag as proof that a payment completed or that your backend authenticated the human user.

## Payment links and paywall assets

Before opening a payment URL, use the client's configured allowlist:

```kotlin
import com.inappify.sdk.InappifyV2Client
import com.inappify.sdk.isPaymentUrlAllowed

fun canOpenPayment(client: InappifyV2Client, url: String): Boolean =
    client.isPaymentUrlAllowed(url)
```

The production payment allowlist contains `pay.inappify.com`. Allowed payment URLs must use HTTPS on port 443, match an exact configured host, and contain no username or password. The purchase and discount-link paths also reject untrusted payment URLs. The SDK does not automatically open a browser or accept an arbitrary redirect destination.

`InappifyPaywallPolicy.sanitizeAsset(asset, assetHosts)` is a separate public helper for a host that handles paywall assets. It filters rendering fields, rejects remote font material, and checks asset URLs against the exact host set passed to that call. The native package fallback does not automatically download remote assets; do not assume the configuration's `assetHosts` field provides an automatic asset loader.

## Session storage, cache and account boundaries

Production persistence uses AES-GCM encrypted files in the application's no-backup directory. Encryption keys are protected by Android Keystore; older Android versions use a Keystore RSA key to wrap the AES key. Atomic writes preserve state across interrupted writes. This protects local storage at rest; it is not a promise that a compromised application process cannot inspect its own in-memory data.

Session-cache fingerprints bind the app key, application package and SDK service configuration. Verified app/project and customer scope are checked before cache publication. Marketplace journals additionally bind the commerce endpoint, app/package, account and store. An operation from another scope is not silently replayed for the current customer. Changing app configuration requires a new client; retain only checkpoints that belong to their original scope.

`configure(options)` loads and verifies matching persisted state. When the retained session remains usable, Configure can complete from that state and schedule background recovery. The separate `restoreCachedSession(options)` extension is not implemented by the production V2 client and returns `UNSUPPORTED_OPERATION`; use Configure and the V2 fetch policies.

`customerInfo(policy)` and `offerings(policy)` support `CACHE_ONLY`, `CACHE_FIRST`, `NETWORK_FIRST`, and `NETWORK_ONLY`. `CACHE_FIRST` can return available verified cache immediately and schedule a background refresh. `NETWORK_FIRST` can fall back to retained cache after a network failure. The current production implementation does not impose a five-minute limit on `CACHE_FIRST`.

Cached CustomerInfo is signature- and scope-checked, but an expired signed envelope may be retained for offline use. Cached data is not proof of current server authorization. The production client's `getEntitlement` / `isActiveEntitlement` helpers also check the entitlement's expiry against the trusted time floor learned from signed server data. Design the application's offline access policy explicitly; do not grant a new purchase because an HTTP call failed.

A pending logout or login transition hides the previous customer's public state and blocks normal session work until reconciliation completes. Keep the host account lifecycle aligned with the SDK and call `recover()` after foregrounding, reconnecting or returning from checkout. `setNetworkEnabled(false)` prevents subsequent SDK/commerce HTTP use while retaining verified cache. It is not a device firewall, a store-disconnect API or an automatic cancellation of every already-running operation.

The public snapshot and state events contain no session credentials or purchase tokens. They can still contain customer identifiers and business data. Their `toString()` redaction does not make manually serializing their properties appropriate for unrestricted logs.

## Attribute privacy

V2 attributes are write-only values. The client removes attributes from verified public CustomerInfo rather than returning stored personal values. `queueAttributes()` persists valid writes in the encrypted account-bound document; `flushAttributes()` sends queued batches with the session Bearer. Null or empty values request deletion. Rejected batches can remain quarantined locally for recovery analysis, so collecting a value has persistence implications even when the server rejects it.

Do not put session credentials, store receipts or application secrets in attributes. Select custom and reserved attributes according to your application's privacy policy, and avoid duplicating their values in analytics or logs. Queue and synchronize targeting attributes before fetching a targeted offering; returned public CustomerInfo is not an attribute readback API.

## Consumable grants and uncertain outcomes

Register `setConsumableDeliveryHandler()` before Configure when the host can fulfill consumables automatically. The handler must apply the grant in durable host storage, with `delivery.deliveryId` as a unique idempotency key, and return `DELIVERED` only after the transaction commits. Returning `RETRY_LATER` leaves delivery pending. The SDK does not decide the quantity to grant from a product identifier.

For a manual flow, first commit the same durable, idempotent host grant, then call `confirmDelivery(deliveryId)`. Calling confirmation is an assertion that the item is already present in the user's inventory. Marketplace consumption happens after server-authoritative delivery and durable confirmation; a subscription or non-consumable must not be consumed as a shortcut to complete recovery.

Recovery persists checkpoints before registration or acknowledgement and retains retry/backoff state. It can resume an acknowledgement whose server result was lost without calling the host grant again. The host must still make its own inventory transaction idempotent: a process can stop after the host grant and before an SDK checkpoint completes.

When an error includes `outcomeMayHaveCommitted`, retain the logical purchase attempt and call `recover()` / `syncPendingConsumables()` rather than opening another store payment. A native success callback is receipt evidence, not an immediate entitlement grant. Refresh verified customer state and inspect the server-authoritative purchase/delivery status. Do not clear app data or edit the encrypted journal to work around a pending payment.

## Bazaar subscriptions with an empty payload

`enableSubscriptionRecoveryWithoutPayload` defaults to `true` in `InappifyOptions`. It allows an owned Bazaar subscription whose developer payload is empty or whitespace to use:

```text
POST https://api.inappify.com/app/v2/store/subscriptions/recover
Authorization: Bearer <sessionToken>
```

Only the Bazaar subscription inventory query qualifies. The receipt must match the host package and include the original signed receipt, signature and positive purchase time. The client preserves that evidence and sends `productIdentifier` plus `purchase`; it does not invent an offering, owner or receipt binding. Nonempty malformed or incorrectly bound payloads do not fall back to this route. Myket and in-app receipts keep their existing recovery paths.

The backend must deploy the recovery endpoint and validate the receipt with Bazaar, establish a link to a previously verified purchase, prove ownership for the current session, and resolve the catalog subscription. Session possession, SKU and a client purchase timestamp alone do not prove ownership or a new billing period. Both recovery and verification-status responses must identify the matching source and product; consumable delivery/consumption states are rejected for this subscription operation.

For an explicit opt-out, construct `InappifyOptions` with `enableSubscriptionRecoveryWithoutPayload = false`. This prevents discovery and polling of these operations without deleting pending checkpoints. Local deduplication compares the token hash and purchase time with freshly verified CustomerInfo. If both stay unchanged, automatic recovery can skip the receipt; authoritative renewal/expiry detection still belongs to the backend. Keep pending operation data intact across SDK upgrades and do not downgrade to a client that cannot decode its operation kind.

## Opt-in HTTP diagnostics

The SDK does not print HTTP traffic to Logcat by default. Register the listener before Configure to capture the first request:

```kotlin
import android.content.Context
import android.util.Log
import com.inappify.sdk.InappifyClient
import com.inappify.sdk.addHttpTraceListener

class DiagnosticClientOwner(context: Context) : AutoCloseable {
    val client = InappifyClient.create(context)
    private val traces = client.addHttpTraceListener { trace ->
        Log.d(
            "InappifyHttp",
            "${trace.method} ${trace.endpoint} status=${trace.statusCode} " +
                "durationMs=${trace.durationMillis} failure=${trace.failure} " +
                "requestId=${trace.requestId}",
        )
    }

    // Configure and use this same retained client.
    override fun close() {
        traces.close()
        client.close()
    }
}
```

A trace includes method, sanitized endpoint, filtered request/response headers and bodies, status, request ID, elapsed time, transport failure and `containsSensitiveData`. To inspect JSON structure, read `trace.requestBody` and `trace.responseBody` explicitly; `trace.toString()` deliberately omits body contents.

Normal production factories provide filtered traces. Authorization and lifecycle-attempt headers are redacted. Sensitive fields include API/session/receipt tokens, receipt JSON, payloads, signatures, customer data, attributes, delivery/verification identifiers and payment URLs. Known request secrets are also scrubbed from responses. Endpoint query/fragment values are omitted and sensitive identifier path segments are replaced. Malformed/non-JSON bodies are omitted, and diagnostic output is bounded rather than a complete wire dump.

Filtering is a diagnostic aid, not a guarantee that every arbitrary application string is anonymous. Use metadata-only logs by default and review any explicitly exported bodies. Keep the listener prompt: callbacks run on a background thread, listener exceptions do not change SDK results, and diagnostics can arrive after the suspend operation returns. Close the registration when finished; it is not a global logging switch and does not enable OkHttp wire logging.

`InappifyUnsafeDebug.createClient(context)` exists only in the SDK's debug source variant. It deliberately exposes sensitive body and endpoint values and marks traces with `containsSensitiveData = true`; do not use it with real accounts or export its output. It is absent from the published release AAR. Making the host application's APK debuggable does not add that factory to a release SDK dependency or disable normal production redaction.

## Investigating a failed purchase

Capture the filtered method/endpoint, status, transport failure, duration and request ID, then inspect `InappifyResult.Failure.error.code`, `isRetryable` and structured `details`. Correlate completed traces by request ID instead of assuming asynchronous callback order matches business events.

| Evidence | What to investigate |
| --- | --- |
| No trace | Listener registration/client lifetime, no completed HTTP exchange yet, a cache hit, or native store work rather than Inappify HTTP |
| `UNAUTHORIZED` / HTTP 401 | Session expiry and the backend's exact error code; Configure uses the bootstrap key, commerce uses session Bearer |
| `MALFORMED_RESPONSE` with signature details | Signing key/alias, signed app/project/customer scope, response CustomerInfo equality, expiry or clock; do not bypass verification |
| `STORE_UNAVAILABLE` | Marketplace installation, account login, public key and UI host requirements; these native operations do not produce Inappify HTTP traces |
| Pending/uncertain purchase or delivery | Retained attempt/checkpoint, server verification state, backoff and durable host fulfillment; recover the same operation |
| Subscription ownership rejection | Backend original-purchase linkage and account ownership; the SDK cannot establish these from an empty payload |

A successful Configure, compilation or synthetic test does not establish that a marketplace renewal or backend ownership check succeeded. Validate those end-to-end paths with the intended deployment and test account while keeping the same durable fulfillment rules.

## Implementation references

- [Public client and lifecycle](../sdk/src/main/kotlin/com/inappify/sdk/InappifyClient.kt), [V2 configuration and policies](../sdk/src/main/kotlin/com/inappify/sdk/InappifyV2.kt)
- [CustomerInfo verification](../sdk/src/main/kotlin/com/inappify/sdk/internal/v2/V2Protocol.kt), [session and recovery owner](../sdk/src/main/kotlin/com/inappify/sdk/internal/v2/GoV2Client.kt)
- [Transport boundaries](../sdk/src/main/kotlin/com/inappify/sdk/internal/network/OkHttpTransport.kt), [diagnostic filtering](../sdk/src/main/kotlin/com/inappify/sdk/internal/network/HttpDiagnosticReporter.kt)
- [Encrypted state](../sdk/src/main/kotlin/com/inappify/sdk/internal/storage/EncryptedSessionStateStore.kt), [durable fulfillment API](../sdk/src/main/kotlin/com/inappify/sdk/InappifyConsumableFulfillment.kt)

See [Getting started](GETTING_STARTED.md), [API reference](API_REFERENCE.md), [Store setup](STORES.md), and [Recovery and cache](RECOVERY_AND_CACHE.md) for integration details.
