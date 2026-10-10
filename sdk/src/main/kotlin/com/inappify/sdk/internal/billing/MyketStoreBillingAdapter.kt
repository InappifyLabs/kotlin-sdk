package com.inappify.sdk.internal.billing

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.inappify.sdk.internal.billing.myket.IabHelper
import com.inappify.sdk.internal.billing.myket.util.Purchase
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

/** Isolated Myket 1.6 integration. Poolakey and the host's own billing classes are untouched. */
internal class MyketStoreBillingAdapter(context: Context, private val rsaPublicKey: String) :
    StoreBillingAdapter, PartialStorePurchaseQueryAdapter {
    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val validator = MyketReceiptValidator(rsaPublicKey, this.context.packageName)
    private val closed = AtomicBoolean()
    private val active = AtomicReference<(() -> Unit)?>(null)

    override suspend fun purchase(uiHost: StoreUiHost, request: StorePurchaseRequest): StoreBillingResult {
        if (request.productType != StoreProductType.IN_APP || request.dynamicPriceToken != null ||
            request.productIdentifier.isBlank()) return StoreBillingResult.Failure(error(StoreBillingErrorCode.INVALID_REQUEST))
        return operation<StoreBillingResult>(uiHost, { StoreBillingResult.Failure(it) }) { helper, complete ->
            val activity = uiHost.activityOrNull()
            if (activity == null) complete(StoreBillingResult.Failure(error(StoreBillingErrorCode.UI_HOST_UNAVAILABLE)))
            else helper.launchPurchaseFlow(activity, request.productIdentifier, { result, purchase ->
                when {
                    result.response in setOf(IabHelper.IABHELPER_USER_CANCELLED, IabHelper.BILLING_RESPONSE_RESULT_USER_CANCELED) ->
                        complete(StoreBillingResult.Cancelled)
                    !result.isSuccess -> complete(StoreBillingResult.Failure(error(StoreBillingErrorCode.PURCHASE_FAILED)))
                    else -> {
                        val receipt = purchase?.let { validator.validate(it.originalJson, it.signature,
                            request.productIdentifier, request.developerPayload.orEmpty()) }
                        complete(if (receipt == null) StoreBillingResult.Failure(error(StoreBillingErrorCode.INVALID_PURCHASE_DATA))
                            else StoreBillingResult.Success(receipt))
                    }
                }
            }, request.developerPayload.orEmpty())
        }
    }

    override suspend fun queryPurchases(productType: StoreProductType): StorePurchaseQueryResult = query(productType, false)
    override suspend fun queryPurchasesPartially(productType: StoreProductType): StorePurchaseQueryResult = query(productType, true)

    private suspend fun query(type: StoreProductType, partial: Boolean): StorePurchaseQueryResult {
        if (type != StoreProductType.IN_APP) return StorePurchaseQueryResult.Failure(error(StoreBillingErrorCode.UNSUPPORTED_MARKET))
        return operation<StorePurchaseQueryResult>(null, { StorePurchaseQueryResult.Failure(it) }) { helper, complete ->
            helper.queryInventoryAsync(false, { result, inventory ->
                if (!result.isSuccess || inventory == null) {
                    complete(StorePurchaseQueryResult.Failure(error(StoreBillingErrorCode.PURCHASE_QUERY_FAILED, true)))
                } else {
                    var invalid = inventory.invalidPurchaseCount
                    val purchases = inventory.allPurchases.mapNotNull {
                        validator.validate(it.originalJson, it.signature).also { value -> if (value == null) invalid++ }
                    }
                    complete(if (!partial && invalid > 0) StorePurchaseQueryResult.Failure(error(StoreBillingErrorCode.INVALID_PURCHASE_DATA))
                        else StorePurchaseQueryResult.Success(purchases, invalid))
                }
            })
        }
    }

    override suspend fun consume(purchase: StorePurchase): StoreConsumeResult {
        val verified = validator.validate(purchase.originalJson, purchase.signature, purchase.productIdentifier)
        if (verified == null || verified.purchaseToken != purchase.purchaseToken || verified.packageName != purchase.packageName)
            return StoreConsumeResult.PermanentFailure(error(StoreBillingErrorCode.INVALID_PURCHASE_DATA))
        return operation(null, { if (it.isRetryable) StoreConsumeResult.RetryableFailure(it) else StoreConsumeResult.PermanentFailure(it) }) { helper, complete ->
            val receipt = Purchase(IabHelper.ITEM_TYPE_INAPP, purchase.originalJson, purchase.signature)
            helper.consumeAsync(receipt) { _, result ->
                complete(when (result.response) {
                    IabHelper.BILLING_RESPONSE_RESULT_OK -> StoreConsumeResult.Success
                    IabHelper.BILLING_RESPONSE_RESULT_DEVELOPER_ERROR -> StoreConsumeResult.PermanentFailure(error(StoreBillingErrorCode.CONSUME_FAILED))
                    else -> StoreConsumeResult.RetryableFailure(error(StoreBillingErrorCode.CONSUME_FAILED, true))
                })
            }
        }
    }

    private suspend fun <T> operation(host: StoreUiHost?, failure: (StoreBillingError) -> T,
        start: (IabHelper, (T) -> Unit) -> Unit): T {
        if (closed.get()) return failure(error(StoreBillingErrorCode.ADAPTER_CLOSED))
        // Reject malformed configuration before a purchase UI can charge the customer.
        if (!validator.hasValidPublicKey) return failure(error(StoreBillingErrorCode.INVALID_REQUEST))
        // The upstream broadcast protocol has application-scoped callbacks. Serialize all Myket helpers.
        if (!operations.tryLock()) return failure(error(StoreBillingErrorCode.PURCHASE_IN_PROGRESS, true))
        return try {
            withTimeoutOrNull(120_000L) {
                suspendCancellableCoroutine { continuation ->
                    val done = AtomicBoolean()
                    var helper: IabHelper? = null
                    var lifecycle: Lifecycle? = null
                    var observer: LifecycleEventObserver? = null
                    fun cleanup() {
                        val release = Runnable {
                            observer?.let { lifecycle?.removeObserver(it) }
                            runCatching { helper?.dispose() }
                            helper = null
                        }
                        if (Looper.myLooper() == Looper.getMainLooper()) release.run() else handler.post(release)
                    }
                    fun finish(value: T) {
                        if (done.compareAndSet(false, true)) {
                            cleanup()
                            if (continuation.isActive) continuation.resume(value)
                        }
                    }
                    val cancel = { finish(failure(error(StoreBillingErrorCode.ADAPTER_CLOSED))) }
                    active.set(cancel)
                    continuation.invokeOnCancellation { if (done.compareAndSet(false, true)) cleanup() }
                    if (!handler.post {
                        if (done.get()) return@post
                        if (closed.get()) { cancel(); return@post }
                        try {
                            if (host != null) {
                                when (val resolved = host.resolveActivityResultRegistry()) {
                                    is StoreUiHostResolution.Failure -> { finish(failure(resolved.error)); return@post }
                                    is StoreUiHostResolution.Success -> {
                                        lifecycle = resolved.lifecycle
                                        observer = LifecycleEventObserver { _, event ->
                                            if (event == Lifecycle.Event.ON_DESTROY)
                                                finish(failure(error(StoreBillingErrorCode.UI_HOST_DESTROYED)))
                                        }
                                        resolved.lifecycle.addObserver(requireNotNull(observer))
                                    }
                                }
                            }
                            if (done.get()) return@post
                            val client = IabHelper(context, rsaPublicKey)
                            helper = client
                            client.startSetup { result ->
                                if (!done.get()) {
                                    if (!result.isSuccess) finish(failure(error(StoreBillingErrorCode.CONNECTION_FAILED, true)))
                                    else try { start(client, ::finish) }
                                    catch (_: Exception) { finish(failure(error(StoreBillingErrorCode.PURCHASE_FAILED))) }
                                }
                            }
                        } catch (_: Exception) { finish(failure(error(StoreBillingErrorCode.CONNECTION_FAILED, true))) }
                    }) finish(failure(error(StoreBillingErrorCode.MAIN_THREAD_UNAVAILABLE)))
                }
            } ?: failure(error(StoreBillingErrorCode.OPERATION_TIMEOUT, true))
        } finally {
            active.set(null)
            operations.unlock()
        }
    }

    override fun close() { closed.set(true); active.getAndSet(null)?.invoke() }

    private fun error(code: StoreBillingErrorCode, retryable: Boolean = false): StoreBillingError =
        StoreBillingError(code, "Myket billing operation did not complete ($code).", retryable)

    private companion object { val operations = Mutex() }
}
