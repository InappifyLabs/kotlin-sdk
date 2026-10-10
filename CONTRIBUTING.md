# Contributing

Use JDK 17, the checked-in Gradle wrapper and Android SDK platform 34. The
library supports Android API 21 and newer. Configure your Android SDK through
`ANDROID_HOME` or an untracked `local.properties` file.

```bash
./gradlew --no-daemon \
  :sdk:testDebugUnitTest \
  :sdk:lintRelease \
  :sdk:assembleRelease \
  :sdk:assembleDebugAndroidTest
```

The last task compiles the instrumented test APK; it does not run device tests.
With a suitable emulator or device, run `:sdk:connectedDebugAndroidTest`.

Public Kotlin APIs use explicit API mode. Keep suspend operations, snapshots,
error codes, session scope and durable purchase state consistent. Changes to
store receipt validation, delivery ordering, journal codecs or retry behavior
need tests that exercise the relevant failure and restart paths. Never infer
ownership or grant a purchase from an unverified store response.

Include the concrete behavior change and validation in your pull request.
Update the relevant guide and KDoc when behavior changes. Examples should use
placeholders and compile against the public API. Do not commit credentials,
customer or receipt data, application integrations, signing files, local
engineering notes, generated artifacts or Android Studio metadata.

```bash
bash scripts/check-publication.sh
git diff --check
```

See [the release process](docs/RELEASING.md) for publication and
[security guidance](docs/SECURITY_AND_DIAGNOSTICS.md) for diagnostics.
