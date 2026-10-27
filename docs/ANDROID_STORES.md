# Android store-specific builds (Flutter 0.2.6 / Kotlin 0.2.6)

Flutter 0.2.6 requires Kotlin v0.2.6 with Gradle Module Metadata and all four
release AARs. Kotlin is published and distribution-verified before Flutter.
Flutter 0.2.5 / Kotlin v0.2.4 still package both stores. An API key selects the
server marketplace at runtime; it cannot change dependencies or permissions in an APK.

## Flutter

Set the following in the application's `android/gradle.properties` before building:

```properties
inappifyStore=myket
```

Build normally (`flutter build apk --release`). For a Bazaar release change it to
`inappifyStore=bazaar`. Supported values are case-sensitive:

| Value | Native billing included | Store billing permissions |
| --- | --- | --- |
| `myket` | Myket only; no Poolakey dependency | `ir.mservices.market.BILLING` |
| `bazaar` | Poolakey only; no Myket client/components | `com.farsitel.bazaar.permission.PAY_THROUGH_BAZAAR` |
| `direct` | Neither store | Neither store permission |
| `allStores` (default) | Both stores | Both permissions |

Other Android/network permissions remain independent of this selection. A host or
another dependency can still declare a store permission; inspect the final APK.
The matching public app API key must still resolve to the intended store.
A store excluded at build time returns `UNSUPPORTED_MARKET` when requested;
changing runtime keys does not enable that adapter.

For isolated CI builds, Gradle accepts `-PinappifyStore=myket` or
`-PinappifyStore=bazaar`. When building through Flutter, this is a **Gradle property**,
not a Dart define; `--dart-define` does not select the native variant. CI can write
the property into its checkout's `android/gradle.properties` before calling Flutter.
Use a separate build invocation for each store. The app's `--flavor myket` alone
does not set the plugin property. Keep both settings aligned if the host has flavors.

## Direct Kotlin consumers

The native SDK publishes four release variants on the same Maven coordinate.
Gradle selects the dependency graph and AAR together using the `inappifyStore`
flavor dimension. For one store:

```kotlin
android {
    defaultConfig {
        missingDimensionStrategy("inappifyStore", "myket")
    }
}
dependencies {
    implementation("com.github.InappifyLabs:kotlin-sdk:v0.2.6")
}
```

For multiple app flavors:

```kotlin
android {
    flavorDimensions += "channel"
    productFlavors {
        create("myket") {
            dimension = "channel"
            missingDimensionStrategy("inappifyStore", "myket")
        }
        create("bazaar") {
            dimension = "channel"
            missingDimensionStrategy("inappifyStore", "bazaar")
        }
    }
}
```

Existing direct Kotlin consumers must add `missingDimensionStrategy` when upgrading;
use `allStores` to preserve both adapters. Flutter supplies it automatically.
Do not include multiple native SDK variants in the same APK.

Keep Gradle Module Metadata enabled in Maven/JitPack repository settings (Gradle's
default). Do not force POM-only resolution, use `@aar`, exclude Poolakey manually,
or copy an AAR without its variant dependencies. The release must include the
`.module` file; a POM alone cannot express per-store dependencies. Verify public
JitPack resolution before publishing Flutter; local artifact checks do not prove
that the remote repository serves the new metadata correctly.

## Safe workaround for Flutter 0.2.5

Yes: removing the unrelated permission in the **app's store-specific manifest** is
supported by Android's manifest merger. For `android/app/src/myket/AndroidManifest.xml`:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">
    <uses-permission
        android:name="com.farsitel.bazaar.permission.PAY_THROUGH_BAZAAR"
        tools:node="remove" />
</manifest>
```

For `src/bazaar/AndroidManifest.xml`, remove `ir.mservices.market.BILLING` instead.
The host must define matching product flavors and build the corresponding flavor.
Do not put either removal in the shared main manifest if another channel needs it.
This workaround removes a manifest declaration; it does not remove bundled billing
code/dependencies. Do not remove the permission of the store used for real billing.
Do not exclude Poolakey from 0.2.5: that native SDK directly references its classes.

## Verification

Check the release dependency report and the final merged manifest/APK for each
store, including minified release builds. Myket must have its billing permission
and no Bazaar permission/Poolakey; Bazaar must keep its permission and no Myket
billing components. Test a real purchase/recovery separately on each store.

Android reference: https://developer.android.com/build/manage-manifests
