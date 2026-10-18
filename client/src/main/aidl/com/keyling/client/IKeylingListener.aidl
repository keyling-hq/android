package com.keyling.client;

oneway interface IKeylingListener {
    /** state is one of Keyling.STATE_*. Called once on registration, then on every change. */
    void onStateChanged(int state);
}
