package com.inappify.sdk.internal.billing

import android.app.Activity
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import java.lang.ref.WeakReference

/**
 * Weak, invocation-scoped reference to the Android host used by native billing UI.
 *
 * The SDK must create a new instance for each purchase call. Billing adapters do not retain the
 * Activity itself and resolve its lifecycle-bound Activity Result registry only when the payment
 * flow starts.
 */
internal class StoreUiHost private constructor(
    activity: Activity,
) {
    private val activityReference: WeakReference<Activity> = WeakReference(activity)

    /** Used only while launching Myket's proxy Activity; adapters must not retain it. */
    fun activityOrNull(): Activity? = activityReference.get()?.takeUnless {
        it.isDestroyed || it.isFinishing
    }

    /** Resolves and validates the registry and lifecycle required by native billing flows. */
    fun resolveActivityResultRegistry(): StoreUiHostResolution {
        val activity = activityReference.get()
            ?: return StoreUiHostResolution.Failure(
                StoreBillingError(
                    code = StoreBillingErrorCode.UI_HOST_UNAVAILABLE,
                    message = "The billing UI host is no longer available.",
                ),
            )

        if (activity.isDestroyed) {
            return StoreUiHostResolution.Failure(
                StoreBillingError(
                    code = StoreBillingErrorCode.UI_HOST_DESTROYED,
                    message = "The billing UI host is destroyed.",
                ),
            )
        }

        if (activity.isFinishing) {
            return StoreUiHostResolution.Failure(
                StoreBillingError(
                    code = StoreBillingErrorCode.UI_HOST_FINISHING,
                    message = "The billing UI host is finishing.",
                ),
            )
        }

        val registryOwner = activity as? ActivityResultRegistryOwner
            ?: return StoreUiHostResolution.Failure(
                StoreBillingError(
                    code = StoreBillingErrorCode.UI_HOST_NOT_SUPPORTED,
                    message = "The billing UI host must implement ActivityResultRegistryOwner.",
                ),
            )

        val lifecycleOwner = activity as? LifecycleOwner
            ?: return StoreUiHostResolution.Failure(
                StoreBillingError(
                    code = StoreBillingErrorCode.UI_HOST_NOT_SUPPORTED,
                    message = "The billing UI host must implement LifecycleOwner.",
                ),
            )

        if (lifecycleOwner.lifecycle.currentState == Lifecycle.State.DESTROYED) {
            return StoreUiHostResolution.Failure(
                StoreBillingError(
                    code = StoreBillingErrorCode.UI_HOST_DESTROYED,
                    message = "The billing UI host lifecycle is destroyed.",
                ),
            )
        }

        return StoreUiHostResolution.Success(
            registry = registryOwner.activityResultRegistry,
            lifecycle = lifecycleOwner.lifecycle,
        )
    }

    /** Does not expose the Activity identity or class name. */
    override fun toString(): String = "StoreUiHost"

    companion object {
        /** Wraps [activity] without keeping it alive beyond the caller's lifecycle. */
        fun from(activity: Activity): StoreUiHost = StoreUiHost(activity)
    }
}

/** Result of resolving a native UI host into its Activity Result and lifecycle owners. */
internal sealed interface StoreUiHostResolution {
    class Success(
        val registry: ActivityResultRegistry,
        val lifecycle: Lifecycle,
    ) : StoreUiHostResolution

    class Failure(val error: StoreBillingError) : StoreUiHostResolution
}
