// Adapted from Myket Billing Client 1.6; see THIRD_PARTY_NOTICES.md.
package com.inappify.sdk.internal.billing.myket.util.communication;


import com.inappify.sdk.internal.billing.myket.util.IabResult;

public interface BillingSupportCommunication {
    void onBillingSupportResult(int response);

    void remoteExceptionHappened(IabResult result);
}
