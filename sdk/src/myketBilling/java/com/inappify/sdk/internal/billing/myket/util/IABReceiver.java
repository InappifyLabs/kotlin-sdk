// Adapted from Myket Billing Client 1.6; see THIRD_PARTY_NOTICES.md.
package com.inappify.sdk.internal.billing.myket.util;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.List;

interface IABReceiverCommunicator {
    void onNewBroadcastReceived(Intent intent);
}

public class IABReceiver extends BroadcastReceiver {

    private static final Object observerLock = new Object();
    private static List<IABReceiverCommunicator> observers = new ArrayList<>();

    static void addObserver(IABReceiverCommunicator communicator) {
        synchronized (observerLock) {
            observers.add(communicator);
        }
    }

    static void removeObserver(IABReceiverCommunicator communicator) {
        synchronized (observerLock) {
            observers.remove(communicator);
        }
    }

    private static void notifyObservers(Intent intent) {
        synchronized (observerLock) {
            for (IABReceiverCommunicator observer : new ArrayList<>(observers)) {
                try { observer.onNewBroadcastReceived(intent); }
                catch (RuntimeException ignored) { /* Malformed broadcasts never crash the host. */ }
            }
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        Intent sendIntent = new Intent();
        sendIntent.setAction(intent.getAction() + ".iab");
        Bundle bundle = intent.getExtras();
        if (bundle != null) {
            sendIntent.putExtras(bundle);
        }
        notifyObservers(sendIntent);
    }
}
