# Releasing the Kotlin SDK

Repository: <https://github.com/InappifyLabs/kotlin-sdk>.

The runtime version in `gradle.properties` is `0.2.6`. Beta versions use
`major.minor.patch-beta.N`; stable versions use `major.minor.patch`. Git tags
prefix this value with `v`, so the prepared tag is `v0.2.6`. JitPack
consumers use the tag verbatim:

```kotlin
implementation("com.github.InappifyLabs:kotlin-sdk:v0.2.6")
```

`BuildConfig.SDK_VERSION`, Configure's `sdkVersion`, SDK user-agent and snapshots
use the runtime version without `v`. Do not add independent version constants.
The SDK package remains `com.inappify.sdk`; repository or artifact names do not
change receipt bindings, application identity or encrypted storage scope.

## Prepare and validate

For v0.2.6, deploy the matching commerce backend before clients: Myket recovery
needs committed purchase type, scope and delivery evidence. Publish and verify
this native tag before Flutter 0.2.6. Local version bumps do not publish artifacts.

1. Update `VERSION_NAME` and the changelog, then review the public tree.
2. Use JDK 17 and the Gradle wrapper to run:

   ```bash
   bash scripts/check-publication.sh
   ./gradlew --no-daemon --stacktrace \
     :sdk:testAllStoresDebugUnitTest \
     :sdk:lintAllStoresRelease :sdk:lintMyketRelease :sdk:lintBazaarRelease :sdk:lintDirectRelease \
     :sdk:assembleRelease \
     :sdk:publishReleasePublicationToMavenLocal \
     :sdk:assembleAllStoresDebugAndroidTest
   ```

3. Run `python3 tools/verify_store_artifacts.py`. Check all four AARs, Gradle
   Module Metadata (`.module`), runtime versions, source archives and legal
   notices. Exclude application integrations, secrets, local files and generated
   test artifacts from the release. Compile/run device tests separately when
   required; compilation alone does not establish store or backend behavior.
4. Commit the reviewed tree, create an annotated tag and push the branch/tag
   normally. Never move a published tag to different source.
5. Check GitHub Actions for the exact commit and tag. Publish the GitHub stable
   release with **prerelease disabled**, attaching all four store AARs and their sources archives.
   Reserve prerelease status for versions with a beta suffix.

## Verify distribution

JitPack builds the release variants and sources from the Git tag using `jitpack.yml`.
The published coordinate must serve the `.module` file and every referenced AAR.
Verify all four `inappifyStore` selections; Myket/direct must not resolve Poolakey.
It does not rerun the automated suites; validation is handled separately by CI. Resolve the real public
coordinate from an independent consumer with JitPack restricted to the SDK
group and Poolakey's group. Do not use MavenLocal or dependency substitution in
this distribution check. Build Debug and Release with R8, compare the received
AAR with the release artifact, and inspect the merged manifest and adapters.

Allow for the initial JitPack build and respect server retry/backoff responses.
Do not replace a tagged artifact to work around a failed download.

Store and server deployment checks remain separate from compilation. In
particular, empty-payload Bazaar subscription recovery requires the commerce
service's `store/subscriptions/recover` endpoint and authoritative owner linkage.
See [Stores](STORES.md) and [Recovery and cache](RECOVERY_AND_CACHE.md).
