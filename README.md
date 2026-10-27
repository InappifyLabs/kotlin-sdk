# Inappify Kotlin SDK

Native Android purchases, customer entitlements, offerings, targeting and durable
purchase recovery for Inappify. The SDK supports Cafe Bazaar, Myket and Direct
checkout through a verified V2 session.

**Release:** `0.2.6` · **Git tag:** `v0.2.6`

Configure, snapshots and SDK targeting report exactly `0.2.6`, without the Git
tag’s `v` prefix. No local version normalization changes the server’s offering
selection.

The Kotlin package is `com.inappify.sdk`. This is an Android library; it does not
provide iOS, Flutter, Google Play Billing or a JavaScript client.

See [store-specific builds](docs/ANDROID_STORES.md) for **0.2.6**
release, its required `missingDimensionStrategy`, and per-store dependencies.

## Install

Use JDK 17 for Gradle. The SDK builds with Gradle 8.7, Android Gradle Plugin
8.5.1, Kotlin 1.9.0 and compile SDK 34, and supports Android API 21 and later.
Keep your application's compatible Android toolchain; JDK 17 is the build JVM,
while the library's JVM bytecode target is 1.8.

Add the SDK and Poolakey groups to an exclusive JitPack repository in
`settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        exclusiveContent {
            forRepository {
                maven {
                    name = "InappifyJitPack"
                    url = uri("https://jitpack.io")
                }
            }
            filter {
                includeGroup("com.github.InappifyLabs")
                includeGroup("com.github.cafebazaar.Poolakey")
            }
        }
    }
}
```

In the app module:

```kotlin
android {
    defaultConfig {
        minSdk = 21
        missingDimensionStrategy("inappifyStore", "allStores")
    }
}

dependencies {
    implementation("com.github.InappifyLabs:kotlin-sdk:v0.2.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
```

Use the complete tag in the dependency version. Both store adapters and their
manifest declarations are included. No separate Myket SDK, manifest placeholder
or host billing-result forwarding is required. See the [getting-started
guide](docs/GETTING_STARTED.md) for marketplace setup and host Activity requirements.

## Create and configure

Keep one client in application or dependency-injection scope. Call suspend APIs
from a coroutine, and release the client only when its owner is permanently
finished.

```kotlin
import android.content.Context
import com.inappify.sdk.InappifyClient
import com.inappify.sdk.InappifyOptions
import com.inappify.sdk.InappifyResult
import com.inappify.sdk.InappifyV2Client

class InappifyOwner(context: Context) : AutoCloseable {
    val client: InappifyV2Client = InappifyClient.create(context)

    suspend fun start(
        appPublicKey: String,
        customerId: String?,
    ): InappifyResult<Unit> = client.configure(
        InappifyOptions(
            apiKey = appPublicKey,
            appUserIdentifier = customerId,
        ),
    )

    override fun close() = client.close()
}
```

The app public key bootstraps Configure. The SDK then sends its verified session
Bearer and keeps session tokens private. Set the route for the Android app in
Inappify: the server-selected `storePlatform` in the verified session determines whether purchases use
`Bazar`, `MyKet` or `DirectAndroid`. A request cannot switch that route.

Register a durable, idempotent consumable delivery handler before Configure if
your app sells consumables. A successful SDK call is not always a finished
payment: `DELIVERY_REQUIRED` still needs a durable grant. Backend workflow states
`PROCESSING` and `CONSUME_REQUIRED` can surface as a retryable/pending Failure
with durable work retained; they require further reconciliation. Grant entitlement access from verified CustomerInfo; grant
consumables exactly once using the server `deliveryId`.

## Documentation

Read customer access with the default cache policy:

```kotlin
when (val result = client.getCustomerInfo()) {
    is InappifyResult.Success -> {
        val pro = result.data.entitlements.active["pro"]
        // result.data.entitlements.all also includes expired/inactive rights.
    }
    is InappifyResult.Failure -> { /* Handle the error. */ }
}
```

The default returns verified cache immediately and refreshes stale information
(five minutes in the foreground, twenty-five hours in the background). Use
`getCustomerInfo(InappifyCacheFetchPolicy.FETCH_CURRENT)` when the caller must
wait for current server information. See the API reference for all four policies.

Purchase a package from the selected offering:

```kotlin
val result = client.purchase(
    InappifyPurchaseParams.Builder(activity, selectedPackage).build(),
)
```

The Package supplies its product, offering, package identifier and server product
type. For Direct checkout, use `Builder(selectedPackage)` without an Activity.
Existing request-based purchases, payment results, cache and durable recovery
remain supported. See the purchase guide below for metadata validation, delivery
and Direct payment URLs.

- [Getting started](docs/GETTING_STARTED.md): configuration, identities, targeted
  offerings, purchases, fulfillment, recovery, events and Android lifecycle.
- [API reference](docs/API_REFERENCE.md): public methods, request/result fields,
  cache policies, supported routes and error handling.
- [Bazaar and Myket](docs/STORES.md): store registration, public RSA keys,
  merged manifests, Activity contracts and product types.
- [Recovery and cache](docs/RECOVERY_AND_CACHE.md): startup/foreground behavior,
  durable pending operations, renewal recovery and offline limits.
- [Security and diagnostics](docs/SECURITY_AND_DIAGNOSTICS.md): session trust,
  ownership boundaries, storage, payment URLs and filtered HTTP traces.
- [Contributing](CONTRIBUTING.md): local build, tests and contribution workflow.
- [Releasing](docs/RELEASING.md): maintainer checks, tags and artifact publication.
- [Changelog](CHANGELOG.md): release notes.

After successful Configure, automatic recovery runs in the background. Foreground
and restored internet trigger recovery when this customer's purchase checkpoints
are unfinished or five minutes have passed since the last successful pass.
Automatic retries wait at least thirty seconds after failure. Recovery is
internal; use `syncStorePurchases()` for a manual purchase check or
`restoreStorePurchases()` for an explicit Restore Purchases action.
Bazaar subscription recovery with a genuinely empty developer payload is
on by default and requires the backend `store/subscriptions/recover` endpoint
with ownership/linkage validation. Recovery remains enabled internally; Configure
only accepts the app API key and optional customer identifier.

Native payment return URLs created for this SDK's capability carry a reserved `payment_v1`
marker, target app ID and return ID. The SDK observes Activity/AndroidX intents
and synchronizes only recognized returns; custom routers can forward URLs with
`handleDeepLink(url)`. Ordinary links do not force recovery. See
[payment-return handling](docs/RECOVERY_AND_CACHE.md#inappify-payment-return-links).
The capability is sent internally per request; older SDKs and existing payments
keep their original return URLs without changing Configure or cache formats.

Purchase waits for active recovery and checks known unfinished paid work for the
same product/customer before opening another payment UI. It returns the earlier
purchase's existing status or failure when applicable, retaining its original
Offering/Package. Unrelated recovery errors do not block otherwise valid new
purchases. Purchase results, fulfillment and persisted formats stay unchanged.

Offerings are prefetched automatically while the app is foreground. Do not
pre-warm them with `getOfferings()` in `Application.onCreate`. Cached reads return
immediately; automatic refresh checks a five-minute freshness window and uses
a thirty-second retry backoff after failure. These timestamps stay in memory;
persisted cache and purchase recovery formats are unchanged.

This release requires testing against your registered, signed app, real store
accounts and configured Inappify backend. Build and synthetic test success do
not establish real purchase or renewal acceptance.

## License

See [LICENSE](LICENSE) and the bundled third-party notices.

## SDK logging

Configure logging before creating/configuring the client:

```kotlin
InappifyClient.logLevel = InappifyLogLevel.DEBUG
```

The default is DEBUG in development and INFO in release. Levels are VERBOSE,
DEBUG, INFO, WARN and ERROR; a level includes more severe messages. Logs use
`[Inappify]` in Logcat. `InappifyClient.logHandler` replaces console output;
assign `DefaultInappifyLogHandler()` to restore it. Handlers can run on a
background thread and must return promptly. Callback failures are isolated.
Messages contain lifecycle, cache, network status and purchase/recovery progress,
never credentials, purchase receipts, customer/attribute values or HTTP bodies.
Filtered `addHttpTraceListener` diagnostics remain a separate opt-in API.
