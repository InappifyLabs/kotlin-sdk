# Third-party notices

## Myket Billing Client 1.6

Source: https://github.com/myketstore/myket-billing-client/tree/1.6
Pinned revision: `c9de652206f0e1d23e3acba9adff57181d5c2eb4`.
The adapted files reside in
`sdk/src/main/java/com/inappify/sdk/internal/billing/myket/`.

Original copyright and license notices are retained in the corresponding source
files. The upstream helper, security, receipt, inventory and Base64 sources
include Google Apache License 2.0 notices. Base64 also retains the original
Robert Harder public-domain attribution. The upstream archive does not contain
a separate root LICENSE/NOTICE file; this notice does not replace or broaden the
individual source notices. A copy of Apache License 2.0 is in the repository
LICENSE and the packaged `META-INF/inappify-myket/LICENSE-Apache-2.0.txt`.

Local adaptations: relocated Java packages and generated Binder Java namespace;
fixed Myket package/bind configuration; uniquely named UI resource and private
proxy Activity; bounded waits, cancellation/disposal and Activity recreation;
per-helper broadcast nonce and single-use setup callbacks; partial invalid
receipt accounting, pagination checks and safe parsing; disabled raw billing
logging. The Binder wire descriptor and transactions remain unchanged. Inappify's
Kotlin adapter independently validates signatures, receipt scope and supported
product types before integrating with its existing durable store coordinator.

The Binder Java file was generated with Android SDK build-tools 34.0.0 `aidl`
from the pinned upstream `IInAppBillingService.aidl`, then its Java package was
relocated. Marketplace RPC method signatures and transaction IDs are retained.
