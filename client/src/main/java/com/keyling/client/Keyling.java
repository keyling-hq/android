package com.keyling.client;

/** Constants of the Keyling hub API. */
public final class Keyling {
    private Keyling() {}

    /** API version this library was built against. */
    public static final int API_VERSION = 1;

    /** Package of the Keyling hub app. */
    public static final String HUB_PACKAGE = "com.keyling.hub";

    /** Action of the hub's API service. */
    public static final String ACTION_BIND = "com.keyling.hub.action.BIND_API";

    /**
     * Action of the hub's approval screen. Start it with startActivityForResult (or an
     * ActivityResultLauncher): it identifies the calling app from that, and returns RESULT_OK if
     * the user allowed it.
     */
    public static final String ACTION_REQUEST_APPROVAL = "com.keyling.hub.action.REQUEST_APPROVAL";

    /** Not connected to the hub, so the state is not known. */
    public static final int STATE_UNKNOWN = -1;
    /** No dongle is paired with the hub. */
    public static final int STATE_NOT_PAIRED = 0;
    /** A dongle is paired but not connected; typing connects to it. */
    public static final int STATE_DISCONNECTED = 1;
    public static final int STATE_CONNECTING = 2;
    public static final int STATE_CONNECTED = 3;
    /** The hub needs the user's attention (permission, Bluetooth off, pairing lost). */
    public static final int STATE_NEEDS_ATTENTION = 4;

    public static final int RESULT_OK = 0;
    /** This app is not approved yet; the hub asks the user with a notification. */
    public static final int RESULT_NOT_APPROVED = 1;
    /** The user denied this app. It can be allowed again in the hub. */
    public static final int RESULT_DENIED = 2;
    /** No dongle reachable, or the hub could not be reached. */
    public static final int RESULT_UNAVAILABLE = 3;
    public static final int RESULT_FAILED = 4;
    public static final int RESULT_ABORTED = 5;
    /** The request was malformed. */
    public static final int RESULT_INVALID = 6;
}
