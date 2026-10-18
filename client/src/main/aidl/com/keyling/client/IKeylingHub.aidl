package com.keyling.client;

import com.keyling.client.IKeylingCallback;
import com.keyling.client.IKeylingListener;

/**
 * The Keyling hub's API. Use it through KeylingClient.
 *
 * Methods are only ever appended to this interface; check getApiVersion() before calling
 * methods newer than version 1.
 */
interface IKeylingHub {
    /** Keyling.API_VERSION of the hub. */
    int getApiVersion();

    /** One of Keyling.STATE_*. */
    int getState();

    /**
     * Types text on the computer the dongle is plugged into. layout names a keyboard layout
     * (e.g. "en-US"), or is null for the hub's default. The callback gets one of
     * Keyling.RESULT_* once the dongle has queued the text, or on failure.
     */
    oneway void typeText(String text, String layout, IKeylingCallback callback);

    /** Stops typing and drops input not typed yet. */
    oneway void abort();

    oneway void registerListener(IKeylingListener listener);

    oneway void unregisterListener(IKeylingListener listener);
}
