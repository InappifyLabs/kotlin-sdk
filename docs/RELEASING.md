# Releasing the Kotlin SDK

Repository: <https://github.com/InappifyLabs/kotlin-sdk>.

The runtime version in `gradle.properties` is `0.2.0`. Beta versions use
`major.minor.patch-beta.N`; stable versions use `major.minor.patch`. Git tags
prefix this value with `v`, so this release is tagged `v0.2.0`. JitPack
consumers use the tag verbatim:

```kotlin
implementation("com.github.InappifyLabs:kotlin-sdk:v0.2.0")
```

`BuildConfig.SDK_VERSION`, Configure's `sdkVersion`, SDK user-agent and snapshots
use the runtime version without `v`. Do not add independent version constants.
The SDK package remains `com.inappify.sdk`; repository or artifact names do not
change receipt bindings, application identity or encrypted storage scope.

## Prepare and validate

1. Update `VERSION_NAME` and the changelog, then review the public tree.
2. Use JDK 17 and the Gradle wrapper to run:

   ```bash
   bash scripts/check-publication.sh
   ./gradlew --no-daemon --stacktrace \
     :sdk:testDebugUnitTest \
     :sdk:lintRelease \
     :sdk:assembleRelease \
     :sdk:publishReleasePublicationToMavenLocal \
     :sdk:assembleDebugAndroidTest
   ```

3. Check the AAR's runtime version, POM metadata, source archive and legal
   notices. Exclude application integrations, secrets, local files and generated
   test artifacts from the release. Compile/run device tests separately when
   required; compilation alone does not establish store or backend behavior.
4. Commit the reviewed tree, create an annotated tag and push the branch/tag
   normally. Never move a published tag to different source.
5. Check GitHub Actions for the exact commit and tag. Publish the GitHub stable
   release with **prerelease disabled**, attaching only the SDK AAR and sources archive.
   Reserve prerelease status for versions with a beta suffix.

## Verify distribution

JitPack builds the AAR and source archive from the Git tag using `jitpack.yml`.
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
