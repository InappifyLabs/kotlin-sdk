package com.inappify.sdk.internal.billing

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import ir.cafebazaar.poolakey.Connection
import ir.cafebazaar.poolakey.Payment
import ir.cafebazaar.poolakey.callback.ConsumeCallback
import ir.cafebazaar.poolakey.callback.PurchaseQueryCallback
import ir.cafebazaar.poolakey.config.PaymentConfiguration
import ir.cafebazaar.poolakey.config.SecurityCheck
import ir.cafebazaar.poolakey.entity.PurchaseInfo
import ir.cafebazaar.poolakey.entity.PurchaseState
import ir.cafebazaar.poolakey.request.PurchaseRequest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

/** Native Cafe Bazaar billing implementation backed by Poolakey 2.2.x. */
internal class BazaarStoreBillingAdapter(
    applicationContext: Context,
    rsaPublicKey: String,
) : StoreBillingAdapter, PartialStorePurchaseQueryAdapter {
    private val applicationContext: Context = applicationContext.applicationContext
    private val paymentConfiguration = PaymentConfiguration(SecurityCheck.Enable(rsaPublicKey))
    private val mainHandler = Handler(Looper.getMainLooper())
    private val purchaseMutex = Mutex()
    private val queryMutex = Mutex()
    private val consumeMutex = Mutex()
    private val closed = AtomicBoolean(false)
    private val activePurchase = AtomicReference<ActivePurchase?>(null)
    private val activeQuery = AtomicReference<ActivePurchaseQuery?>(null)
    private val activeConsume = AtomicReference<ActiveConsume?>(null)

    override suspend fun purchase(
        uiHost: StoreUiHost,
        request: StorePurchaseRequest,
    ): StoreBillingResult {
        if (closed.get()) return closedResult()
        if (!purchaseMutex.tryLock()) {
            return failure(
                code = StoreBillingErrorCode.PURCHASE_IN_PROGRESS,
                message = "Another native purchase is already in progress.",
                isRetryable = true,
            )
        }

        return try {
            if (closed.get()) return closedResult()

            val productIdentifier = request.productIdentifier
            if (productIdentifier.isEmpty()) {
                return failure(
                    code = StoreBillingErrorCode.INVALID_REQUEST,
                    message = "A non-empty product identifier is required.",
                )
            }

            executePurchase(
                uiHost = uiHost,
                request = request,
                productIdentifier = productIdentifier,
            )
        } finally {
            purchaseMutex.unlock()
        }
    }

    override suspend fun queryPurchases(
        productType: StoreProductType,
    ): StorePurchaseQueryResult = queryMutex.withLock {
        if (closed.get()) return@withLock closedQueryResult()
        executePurchaseQuery(
            productType = productType,
            mode = StorePurchaseQueryMode.STRICT,
        )
    }

    override suspend fun queryPurchasesPartially(
        productType: StoreProductType,
    ): StorePurchaseQueryResult = queryMutex.withLock {
        if (closed.get()) return@withLock closedQueryResult()
        executePurchaseQuery(
            productType = productType,
            mode = StorePurchaseQueryMode.PARTIAL,
        )
    }

    override suspend fun consume(
        purchase: StorePurchase,
    ): StoreConsumeResult = consumeMutex.withLock {
        if (closed.get()) return@withLock closedConsumeResult()
        if (purchase.purchaseToken.isEmpty()) {
            return@withLock consumeFailure(
                code = StoreBillingErrorCode.INVALID_PURCHASE_DATA,
                message = "Cafe Bazaar purchase evidence does not contain a consume token.",
                isRetryable = false,
            )
        }
        executeConsume(purchase.purchaseToken)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        activePurchase.get()?.complete(closedResult())
        activeQuery.get()?.complete(closedQueryResult())
        activeConsume.get()?.complete(closedConsumeResult())
    }

    private suspend fun executePurchase(
        uiHost: StoreUiHost,
        request: StorePurchaseRequest,
        productIdentifier: String,
    ): StoreBillingResult {
        val connectionLease = ConnectionLease(mainHandler)

        return try {
            awaitPurchase(
                uiHost = uiHost,
                request = request,
                productIdentifier = productIdentifier,
                connectionLease = connectionLease,
            )
        } finally {
            // Poolakey registers Activity Result launchers per Payment instance. Disconnecting the
            // matching Connection unregisters them and must happen exactly once for every attempt.
            connectionLease.disconnectExactlyOnce()
        }
    }

    private suspend fun executePurchaseQuery(
        productType: StoreProductType,
        mode: StorePurchaseQueryMode,
    ): StorePurchaseQueryResult {
        val connectionLease = ConnectionLease(mainHandler)

        return try {
            awaitPurchaseQuery(
                productType = productType,
                mode = mode,
                connectionLease = connectionLease,
            )
        } finally {
            // A query owns a fresh Payment and Connection. Releasing that exact connection here
            // prevents callbacks from one recovery attempt leaking into a later attempt.
            connectionLease.disconnectExactlyOnce()
        }
    }

    private suspend fun executeConsume(
        purchaseToken: String,
    ): StoreConsumeResult {
        val connectionLease = ConnectionLease(mainHandler)

        return try {
            awaitConsume(
                purchaseToken = purchaseToken,
                connectionLease = connectionLease,
            )
        } finally {
            // Consumption owns a fresh Payment and Connection. The token is deliberately kept
            // inside this adapter and is never included in cleanup diagnostics.
            connectionLease.disconnectExactlyOnce()
        }
    }

    private suspend fun awaitPurchase(
        uiHost: StoreUiHost,
        request: StorePurchaseRequest,
        productIdentifier: String,
        connectionLease: ConnectionLease,
    ): StoreBillingResult = suspendCancellableCoroutine { continuation ->
        val lifecycleLease = LifecycleObserverLease(mainHandler)
        val operation = ActivePurchase(
            continuation = continuation,
            onTerminal = { completed ->
                lifecycleLease.removeExactlyOnce()
                activePurchase.compareAndSet(completed, null)
            },
        )

        if (!activePurchase.compareAndSet(null, operation)) {
            operation.complete(
                failure(
                    code = StoreBillingErrorCode.PURCHASE_IN_PROGRESS,
                    message = "Another native purchase is already in progress.",
                    isRetryable = true,
                ),
            )
            return@suspendCancellableCoroutine
        }

        continuation.invokeOnCancellation {
            operation.cancel()
            connectionLease.disconnectExactlyOnce()
        }

        if (closed.get()) {
            operation.complete(closedResult())
            return@suspendCancellableCoroutine
        }

        val posted = mainHandler.post {
            if (!operation.isActive || closed.get()) {
                operation.complete(closedResult())
                return@post
            }

            val resolvedHost = when (val hostResolution = uiHost.resolveActivityResultRegistry()) {
                is StoreUiHostResolution.Success -> hostResolution
                is StoreUiHostResolution.Failure -> {
                    operation.complete(StoreBillingResult.Failure(hostResolution.error))
                    return@post
                }
            }

            val lifecycleObserver = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_DESTROY) {
                    operation.complete(uiHostDestroyedResult())
                }
            }
            try {
                if (!lifecycleLease.register(resolvedHost.lifecycle, lifecycleObserver)) {
                    return@post
                }
            } catch (throwable: Exception) {
                operation.complete(
                    failureFromThrowable(
                        code = StoreBillingErrorCode.UI_HOST_NOT_SUPPORTED,
                        message = "The billing UI host lifecycle could not be observed.",
                        throwable = throwable,
                        isRetryable = true,
                    ),
                )
                return@post
            }
            if (!operation.isActive) return@post

            try {
                val payment = Payment(applicationContext, paymentConfiguration)
                val connection = payment.connect {
                    connectionSucceed {
                        if (!operation.isActive || closed.get()) {
                            operation.complete(closedResult())
                            return@connectionSucceed
                        }

                        val poolakeyRequest = PurchaseRequest(
                            productId = productIdentifier,
                            payload = request.developerPayload,
                            dynamicPriceToken = request.dynamicPriceToken,
                        )

                        try {
                            val purchaseCallback: ir.cafebazaar.poolakey.callback.PurchaseCallback.() -> Unit = {
                                purchaseSucceed { purchaseInfo ->
                                    operation.complete(
                                        validatePurchase(
                                            purchaseInfo = purchaseInfo,
                                            requestedProductIdentifier = productIdentifier,
                                        ),
                                    )
                                }
                                purchaseCanceled {
                                    operation.complete(StoreBillingResult.Cancelled)
                                }
                                purchaseFailed { throwable ->
                                    operation.complete(
                                        failureFromThrowable(
                                            code = StoreBillingErrorCode.PURCHASE_FAILED,
                                            message = "Cafe Bazaar did not complete the purchase.",
                                            throwable = throwable,
                                            isRetryable = true,
                                        ),
                                    )
                                }
                                failedToBeginFlow { throwable ->
                                    operation.complete(
                                        failureFromThrowable(
                                            code = StoreBillingErrorCode.PURCHASE_FLOW_FAILED,
                                            message = "Cafe Bazaar could not start the purchase flow.",
                                            throwable = throwable,
                                            isRetryable = true,
                                        ),
                                    )
                                }
                            }

                            when (request.productType) {
                                StoreProductType.IN_APP -> payment.purchaseProduct(
                                    resolvedHost.registry,
                                    poolakeyRequest,
                                    purchaseCallback,
                                )

                                StoreProductType.SUBSCRIPTION -> payment.subscribeProduct(
                                    resolvedHost.registry,
                                    poolakeyRequest,
                                    purchaseCallback,
                                )
                            }
                        } catch (throwable: Exception) {
                            operation.complete(
                                failureFromThrowable(
                                    code = StoreBillingErrorCode.PURCHASE_FLOW_FAILED,
                                    message = "Cafe Bazaar could not start the purchase flow.",
                                    throwable = throwable,
                                    isRetryable = true,
                                ),
                            )
                        }
                    }
                    connectionFailed { throwable ->
                        operation.complete(
                            failureFromThrowable(
                                code = StoreBillingErrorCode.CONNECTION_FAILED,
                                message = "The Cafe Bazaar billing service is unavailable.",
                                throwable = throwable,
                                isRetryable = true,
                            ),
                        )
                    }
                    disconnected {
                        operation.complete(
                            failure(
                                code = StoreBillingErrorCode.CONNECTION_LOST,
                                message = "The Cafe Bazaar billing connection was interrupted.",
                                isRetryable = true,
                            ),
                        )
                    }
                }
                connectionLease.attach(connection)
            } catch (throwable: Exception) {
                operation.complete(
                    failureFromThrowable(
                        code = StoreBillingErrorCode.CONNECTION_FAILED,
                        message = "The Cafe Bazaar billing service is unavailable.",
                        throwable = throwable,
                        isRetryable = true,
                    ),
                )
            }
        }

        if (!posted) {
            operation.complete(
                failure(
                    code = StoreBillingErrorCode.MAIN_THREAD_UNAVAILABLE,
                    message = "The Android main thread is unavailable for billing.",
                    isRetryable = true,
                ),
            )
        }
    }

    private suspend fun awaitPurchaseQuery(
        productType: StoreProductType,
        mode: StorePurchaseQueryMode,
        connectionLease: ConnectionLease,
    ): StorePurchaseQueryResult = suspendCancellableCoroutine { continuation ->
        val purchasePages = mutableListOf<PurchaseInfo>()
        var pageGeneration = 0L
        val operation = ActivePurchaseQuery(
            continuation = continuation,
            onTerminal = { completed -> activeQuery.compareAndSet(completed, null) },
        )

        if (!activeQuery.compareAndSet(null, operation)) {
            operation.complete(
                queryFailure(
                    code = StoreBillingErrorCode.PURCHASE_QUERY_FAILED,
                    message = "Another Cafe Bazaar purchase query is already in progress.",
                    isRetryable = true,
                ),
            )
            return@suspendCancellableCoroutine
        }

        continuation.invokeOnCancellation {
            operation.cancel()
            connectionLease.disconnectExactlyOnce()
        }

        if (closed.get()) {
            operation.complete(closedQueryResult())
            return@suspendCancellableCoroutine
        }

        val posted = mainHandler.post {
            if (!operation.isActive || closed.get()) {
                operation.complete(closedQueryResult())
                return@post
            }

            try {
                val payment = Payment(applicationContext, paymentConfiguration)
                val connection = payment.connect {
                    connectionSucceed {
                        if (!operation.isActive || closed.get()) {
                            operation.complete(closedQueryResult())
                            return@connectionSucceed
                        }

                        val queryCallback: PurchaseQueryCallback.() -> Unit = {
                            querySucceed { purchaseInfos ->
                                if (!operation.isActive) return@querySucceed
                                purchasePages += purchaseInfos
                                pageGeneration += 1
                                val scheduledGeneration = pageGeneration
                                val scheduled = mainHandler.postDelayed(
                                    {
                                        if (
                                            operation.isActive &&
                                            scheduledGeneration == pageGeneration
                                        ) {
                                            operation.complete(
                                                validateQueriedPurchases(
                                                    purchasePages.distinctBy {
                                                        it.purchaseToken
                                                    },
                                                    mode = mode,
                                                ),
                                            )
                                        }
                                    },
                                    QUERY_PAGE_SETTLE_MILLIS,
                                )
                                if (!scheduled) {
                                    operation.complete(
                                        queryFailure(
                                            code = StoreBillingErrorCode.MAIN_THREAD_UNAVAILABLE,
                                            message = "The Android main thread is unavailable for billing.",
                                            isRetryable = true,
                                        ),
                                    )
                                }
                            }
                            queryFailed { throwable ->
                                operation.complete(
                                    queryFailureFromThrowable(
                                        code = StoreBillingErrorCode.PURCHASE_QUERY_FAILED,
                                        message = "Cafe Bazaar could not query owned purchases.",
                                        throwable = throwable,
                                        isRetryable = true,
                                    ),
                                )
                            }
                        }

                        try {
                            when (productType) {
                                StoreProductType.IN_APP ->
                                    payment.getPurchasedProducts(queryCallback)

                                StoreProductType.SUBSCRIPTION ->
                                    payment.getSubscribedProducts(queryCallback)
                            }
                        } catch (throwable: Exception) {
                            operation.complete(
                                queryFailureFromThrowable(
                                    code = StoreBillingErrorCode.PURCHASE_QUERY_FAILED,
                                    message = "Cafe Bazaar could not query owned purchases.",
                                    throwable = throwable,
                                    isRetryable = true,
                                ),
                            )
                        }
                    }
                    connectionFailed { throwable ->
                        operation.complete(
                            queryFailureFromThrowable(
                                code = StoreBillingErrorCode.CONNECTION_FAILED,
                                message = "The Cafe Bazaar billing service is unavailable.",
                                throwable = throwable,
                                isRetryable = true,
                            ),
                        )
                    }
                    disconnected {
                        operation.complete(
                            queryFailure(
                                code = StoreBillingErrorCode.CONNECTION_LOST,
                                message = "The Cafe Bazaar billing connection was interrupted.",
                                isRetryable = true,
                            ),
                        )
                    }
                }
                connectionLease.attach(connection)
            } catch (throwable: Exception) {
                operation.complete(
                    queryFailureFromThrowable(
                        code = StoreBillingErrorCode.CONNECTION_FAILED,
                        message = "The Cafe Bazaar billing service is unavailable.",
                        throwable = throwable,
                        isRetryable = true,
                    ),
                )
            }
        }

        if (!posted) {
            operation.complete(
                queryFailure(
                    code = StoreBillingErrorCode.MAIN_THREAD_UNAVAILABLE,
                    message = "The Android main thread is unavailable for billing.",
                    isRetryable = true,
                ),
            )
        }
    }

    private suspend fun awaitConsume(
        purchaseToken: String,
        connectionLease: ConnectionLease,
    ): StoreConsumeResult = suspendCancellableCoroutine { continuation ->
        val operation = ActiveConsume(
            continuation = continuation,
            onTerminal = { completed -> activeConsume.compareAndSet(completed, null) },
        )

        if (!activeConsume.compareAndSet(null, operation)) {
            operation.complete(
                consumeFailure(
                    code = StoreBillingErrorCode.PURCHASE_IN_PROGRESS,
                    message = "Another Cafe Bazaar consume operation is already in progress.",
                    isRetryable = true,
                ),
            )
            return@suspendCancellableCoroutine
        }

        continuation.invokeOnCancellation {
            operation.cancel()
            connectionLease.disconnectExactlyOnce()
        }

        if (closed.get()) {
            operation.complete(closedConsumeResult())
            return@suspendCancellableCoroutine
        }

        val posted = mainHandler.post {
            if (!operation.isActive || closed.get()) {
                operation.complete(closedConsumeResult())
                return@post
            }

            try {
                val payment = Payment(applicationContext, paymentConfiguration)
                val connection = payment.connect {
                    connectionSucceed {
                        if (!operation.isActive || closed.get()) {
                            operation.complete(closedConsumeResult())
                            return@connectionSucceed
                        }

                        val consumeCallback: ConsumeCallback.() -> Unit = {
                            consumeSucceed {
                                operation.complete(StoreConsumeResult.Success)
                            }
                            consumeFailed { throwable ->
                                operation.complete(
                                    consumeFailureFromThrowable(
                                        code = StoreBillingErrorCode.CONSUME_FAILED,
                                        message = "Cafe Bazaar did not consume the purchase.",
                                        throwable = throwable,
                                        isRetryable = true,
                                    ),
                                )
                            }
                        }

                        try {
                            payment.consumeProduct(purchaseToken, consumeCallback)
                        } catch (throwable: Exception) {
                            operation.complete(
                                consumeFailureFromThrowable(
                                    code = StoreBillingErrorCode.CONSUME_FAILED,
                                    message = "Cafe Bazaar could not start purchase consumption.",
                                    throwable = throwable,
                                    isRetryable = true,
                                ),
                            )
                        }
                    }
                    connectionFailed { throwable ->
                        operation.complete(
                            consumeFailureFromThrowable(
                                code = StoreBillingErrorCode.CONNECTION_FAILED,
                                message = "The Cafe Bazaar billing service is unavailable.",
                                throwable = throwable,
                                isRetryable = true,
                            ),
                        )
                    }
                    disconnected {
                        operation.complete(
                            consumeFailure(
                                code = StoreBillingErrorCode.CONNECTION_LOST,
                                message = "The Cafe Bazaar billing connection was interrupted.",
                                isRetryable = true,
                            ),
                        )
                    }
                }
                connectionLease.attach(connection)
            } catch (throwable: Exception) {
                operation.complete(
                    consumeFailureFromThrowable(
                        code = StoreBillingErrorCode.CONNECTION_FAILED,
                        message = "The Cafe Bazaar billing service is unavailable.",
                        throwable = throwable,
                        isRetryable = true,
                    ),
                )
            }
        }

        if (!posted) {
            operation.complete(
                consumeFailure(
                    code = StoreBillingErrorCode.MAIN_THREAD_UNAVAILABLE,
                    message = "The Android main thread is unavailable for billing.",
                    isRetryable = true,
                ),
            )
        }
    }

    private fun validatePurchase(
        purchaseInfo: PurchaseInfo,
        requestedProductIdentifier: String?,
    ): StoreBillingResult {
        if (purchaseInfo.purchaseState != PurchaseState.PURCHASED) {
            return failure(
                code = StoreBillingErrorCode.INVALID_PURCHASE_STATE,
                message = "Cafe Bazaar returned a purchase that is not in the purchased state.",
            )
        }

        // Validate Bazaar evidence exactly as returned. Never normalize a token
        // or identifier before verification and submission.
        val purchaseToken = purchaseInfo.purchaseToken
        val productIdentifier = purchaseInfo.productId
        val packageName = purchaseInfo.packageName
        if (purchaseToken.isEmpty() || productIdentifier.isEmpty() || packageName.isEmpty()) {
            return failure(
                code = StoreBillingErrorCode.INVALID_PURCHASE_DATA,
                message = "Cafe Bazaar returned incomplete purchase evidence.",
            )
        }

        if (
            requestedProductIdentifier != null &&
            productIdentifier != requestedProductIdentifier
        ) {
            return failure(
                code = StoreBillingErrorCode.PRODUCT_MISMATCH,
                message = "The purchased product does not match the requested product.",
            )
        }

        if (packageName != applicationContext.packageName) {
            return failure(
                code = StoreBillingErrorCode.PACKAGE_MISMATCH,
                message = "The purchased package does not match the host application.",
            )
        }

        return StoreBillingResult.Success(
            StorePurchase(
                orderIdentifier = purchaseInfo.orderId,
                purchaseToken = purchaseToken,
                developerPayload = purchaseInfo.payload,
                packageName = packageName,
                productIdentifier = productIdentifier,
                purchaseTimeMillis = purchaseInfo.purchaseTime,
                originalJson = purchaseInfo.originalJson,
                signature = purchaseInfo.dataSignature,
            ),
        )
    }

    private fun validateQueriedPurchases(
        purchaseInfos: List<PurchaseInfo>,
        mode: StorePurchaseQueryMode,
    ): StorePurchaseQueryResult {
        val purchases = ArrayList<StorePurchase>(purchaseInfos.size)
        var invalidPurchaseCount = 0
        for (purchaseInfo in purchaseInfos) {
            when (
                val validation = validatePurchase(
                    purchaseInfo = purchaseInfo,
                    requestedProductIdentifier = null,
                )
            ) {
                is StoreBillingResult.Success -> purchases += validation.purchase
                is StoreBillingResult.Failure -> {
                    if (mode == StorePurchaseQueryMode.STRICT) {
                        return StorePurchaseQueryResult.Failure(validation.error)
                    }
                    invalidPurchaseCount += 1
                }

                StoreBillingResult.Cancelled -> {
                    if (mode == StorePurchaseQueryMode.STRICT) {
                        return queryFailure(
                            code = StoreBillingErrorCode.INVALID_PURCHASE_DATA,
                            message = "Cafe Bazaar returned invalid purchase evidence.",
                        )
                    }
                    invalidPurchaseCount += 1
                }
            }
        }

        return StorePurchaseQueryResult.Success(
            purchases = purchases.toList(),
            invalidPurchaseCount = invalidPurchaseCount,
        )
    }

    private class ActivePurchase(
        private val continuation: CancellableContinuation<StoreBillingResult>,
        private val onTerminal: (ActivePurchase) -> Unit,
    ) {
        private val terminal = AtomicBoolean(false)

        val isActive: Boolean
            get() = !terminal.get() && continuation.isActive

        fun complete(result: StoreBillingResult) {
            if (!terminal.compareAndSet(false, true)) return
            onTerminal(this)
            if (continuation.isActive) continuation.resume(result)
        }

        fun cancel() {
            if (!terminal.compareAndSet(false, true)) return
            onTerminal(this)
        }
    }

    private class ActivePurchaseQuery(
        private val continuation: CancellableContinuation<StorePurchaseQueryResult>,
        private val onTerminal: (ActivePurchaseQuery) -> Unit,
    ) {
        private val terminal = AtomicBoolean(false)

        val isActive: Boolean
            get() = !terminal.get() && continuation.isActive

        fun complete(result: StorePurchaseQueryResult) {
            if (!terminal.compareAndSet(false, true)) return
            onTerminal(this)
            if (continuation.isActive) continuation.resume(result)
        }

        fun cancel() {
            if (!terminal.compareAndSet(false, true)) return
            onTerminal(this)
        }
    }

    private class ActiveConsume(
        private val continuation: CancellableContinuation<StoreConsumeResult>,
        private val onTerminal: (ActiveConsume) -> Unit,
    ) {
        private val terminal = AtomicBoolean(false)

        val isActive: Boolean
            get() = !terminal.get() && continuation.isActive

        fun complete(result: StoreConsumeResult) {
            if (!terminal.compareAndSet(false, true)) return
            onTerminal(this)
            if (continuation.isActive) continuation.resume(result)
        }

        fun cancel() {
            if (!terminal.compareAndSet(false, true)) return
            onTerminal(this)
        }
    }

    /** Owns one lifecycle observer and removes it exactly once on every terminal path. */
    private class LifecycleObserverLease(
        private val mainHandler: Handler,
    ) {
        private val lock = Any()
        private var registration: LifecycleObserverRegistration? = null
        private var removalRequested: Boolean = false
        private var removalInvoked: Boolean = false

        fun register(
            lifecycle: Lifecycle,
            observer: LifecycleEventObserver,
        ): Boolean {
            synchronized(lock) {
                if (removalRequested) return false
                check(registration == null) {
                    "A lifecycle observer is already registered for this purchase attempt."
                }
                registration = LifecycleObserverRegistration(lifecycle, observer)
            }

            lifecycle.addObserver(observer)
            return true
        }

        fun removeExactlyOnce() {
            val activeRegistration = synchronized(lock) {
                removalRequested = true
                val current = registration ?: return
                if (removalInvoked) return
                removalInvoked = true
                current
            }

            val removalAction = Runnable {
                try {
                    activeRegistration.lifecycle.removeObserver(activeRegistration.observer)
                } catch (_: Exception) {
                    // The purchase is already terminal; lifecycle cleanup cannot be retried safely.
                }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) {
                removalAction.run()
            } else if (!mainHandler.post(removalAction)) {
                removalAction.run()
            }
        }

        private class LifecycleObserverRegistration(
            val lifecycle: Lifecycle,
            val observer: LifecycleEventObserver,
        )
    }

    private class ConnectionLease(
        private val mainHandler: Handler,
    ) {
        private val connection = AtomicReference<Connection?>(null)
        private val disconnectRequested = AtomicBoolean(false)
        private val disconnectInvoked = AtomicBoolean(false)

        fun attach(value: Connection) {
            if (!connection.compareAndSet(null, value)) {
                // This adapter creates exactly one Poolakey connection per attempt.
                return
            }
            invokeDisconnectIfReady()
        }

        fun disconnectExactlyOnce() {
            disconnectRequested.set(true)
            invokeDisconnectIfReady()
        }

        private fun invokeDisconnectIfReady() {
            if (!disconnectRequested.get()) return
            val activeConnection = connection.get() ?: return
            if (!disconnectInvoked.compareAndSet(false, true)) return

            val disconnectAction = Runnable {
                try {
                    activeConnection.disconnect()
                } catch (_: Exception) {
                    // The connection lease is terminal and cannot retry safely.
                }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) {
                disconnectAction.run()
            } else if (!mainHandler.post(disconnectAction)) {
                disconnectAction.run()
            }
        }
    }

    companion object {
        /** Poolakey emits one callback per continuation page but no terminal-page callback. */
        private const val QUERY_PAGE_SETTLE_MILLIS = 2_000L

        private fun closedResult(): StoreBillingResult = failure(
            code = StoreBillingErrorCode.ADAPTER_CLOSED,
            message = "The Cafe Bazaar billing adapter is closed.",
        )

        private fun closedQueryResult(): StorePurchaseQueryResult = queryFailure(
            code = StoreBillingErrorCode.ADAPTER_CLOSED,
            message = "The Cafe Bazaar billing adapter is closed.",
        )

        private fun closedConsumeResult(): StoreConsumeResult = consumeFailure(
            code = StoreBillingErrorCode.ADAPTER_CLOSED,
            message = "The Cafe Bazaar billing adapter is closed.",
            isRetryable = false,
        )

        private fun uiHostDestroyedResult(): StoreBillingResult = failure(
            code = StoreBillingErrorCode.UI_HOST_DESTROYED,
            message = "The billing UI host was destroyed before the purchase completed.",
            isRetryable = true,
        )

        private fun failure(
            code: StoreBillingErrorCode,
            message: String,
            isRetryable: Boolean = false,
        ): StoreBillingResult.Failure = StoreBillingResult.Failure(
            StoreBillingError(
                code = code,
                message = message,
                isRetryable = isRetryable,
            ),
        )

        private fun failureFromThrowable(
            code: StoreBillingErrorCode,
            message: String,
            throwable: Throwable,
            isRetryable: Boolean,
        ): StoreBillingResult.Failure = StoreBillingResult.Failure(
            StoreBillingError(
                code = code,
                message = message,
                isRetryable = isRetryable,
                causeType = throwable::class.java.name,
            ),
        )

        private fun queryFailure(
            code: StoreBillingErrorCode,
            message: String,
            isRetryable: Boolean = false,
        ): StorePurchaseQueryResult.Failure = StorePurchaseQueryResult.Failure(
            StoreBillingError(
                code = code,
                message = message,
                isRetryable = isRetryable,
            ),
        )

        private fun queryFailureFromThrowable(
            code: StoreBillingErrorCode,
            message: String,
            throwable: Throwable,
            isRetryable: Boolean,
        ): StorePurchaseQueryResult.Failure = StorePurchaseQueryResult.Failure(
            StoreBillingError(
                code = code,
                message = message,
                isRetryable = isRetryable,
                causeType = throwable::class.java.name,
            ),
        )

        private fun consumeFailure(
            code: StoreBillingErrorCode,
            message: String,
            isRetryable: Boolean,
        ): StoreConsumeResult {
            val error = StoreBillingError(
                code = code,
                message = message,
                isRetryable = isRetryable,
            )
            return if (isRetryable) {
                StoreConsumeResult.RetryableFailure(error)
            } else {
                StoreConsumeResult.PermanentFailure(error)
            }
        }

        private fun consumeFailureFromThrowable(
            code: StoreBillingErrorCode,
            message: String,
            throwable: Throwable,
            isRetryable: Boolean,
        ): StoreConsumeResult {
            val error = StoreBillingError(
                code = code,
                message = message,
                isRetryable = isRetryable,
                causeType = throwable::class.java.name,
            )
            return if (isRetryable) {
                StoreConsumeResult.RetryableFailure(error)
            } else {
                StoreConsumeResult.PermanentFailure(error)
            }
        }
    }
}
