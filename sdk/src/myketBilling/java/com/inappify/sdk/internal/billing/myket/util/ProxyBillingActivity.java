// Adapted from Myket Billing Client 1.6; see THIRD_PARTY_NOTICES.md.
package com.inappify.sdk.internal.billing.myket.util;

import static com.inappify.sdk.internal.billing.myket.IabHelper.RESPONSE_BUY_INTENT;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Bundle;
import android.os.ResultReceiver;

/** Private trampoline: the host does not need an onActivityResult override. */
public class ProxyBillingActivity extends Activity {
    public static final String BILLING_RECEIVER_KEY = "billing_receiver";
    public static final String PURCHASE_RESULT = "purchase_result";
    private static final int REQUEST_CODE = 100;
    private ResultReceiver receiver;

    @Override protected void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        try {
            receiver = (savedState == null ? getIntent().getExtras() : savedState)
                    .getParcelable(BILLING_RECEIVER_KEY);
            if (receiver == null) { finish(); return; }
            // Rotation must not launch a second payment.
            if (savedState != null) return;
            Object target = getIntent().getParcelableExtra(RESPONSE_BUY_INTENT);
            if (target instanceof PendingIntent) {
                startIntentSenderForResult(((PendingIntent) target).getIntentSender(),
                        REQUEST_CODE, new Intent(), 0, 0, 0);
            } else if (target instanceof Intent) {
                startActivityForResult((Intent) target, REQUEST_CODE);
            } else complete(RESULT_FIRST_USER, null);
        } catch (Exception ignored) { complete(RESULT_FIRST_USER, null); }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putParcelable(BILLING_RECEIVER_KEY, receiver);
        super.onSaveInstanceState(state);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == REQUEST_CODE) complete(result, data);
    }

    private void complete(int result, Intent data) {
        if (receiver != null) {
            Bundle response = new Bundle();
            response.putParcelable(PURCHASE_RESULT, data);
            receiver.send(result, response);
            receiver = null;
        }
        finish();
    }
}
