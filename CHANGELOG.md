# Changelog

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
