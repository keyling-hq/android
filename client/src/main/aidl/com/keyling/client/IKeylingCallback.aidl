package com.keyling.client;

oneway interface IKeylingCallback {
    /** result is one of Keyling.RESULT_*; message is a human-readable detail, or null. */
    void onResult(int result, String message);
}
