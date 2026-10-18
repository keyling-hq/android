package com.keyling.client;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;

/**
 * Connection to the Keyling hub app. All callbacks run on the main thread.
 *
 * Typical use: create it, {@link #bind()}, wait for {@link Listener#onConnected()}, call
 * {@link #typeText}, and {@link #unbind()} when done. Code without a lifecycle of its own, such
 * as a broadcast receiver, can use {@link #typeOnce} instead.
 *
 * The first request from an app returns {@link Keyling#RESULT_NOT_APPROVED} until the user
 * approves it, either from the hub's notification or from {@link #approvalIntent()}.
 */
public final class KeylingClient {
    public interface Listener {
        /** Bound to the hub; requests can be made. */
        void onConnected();

        /** The connection to the hub was lost, e.g. because the hub was updated. */
        void onDisconnected();

        /** The hub's state changed; one of Keyling.STATE_*. */
        void onStateChanged(int state);
    }

    public interface ResultCallback {
        /** result is one of Keyling.RESULT_*; message is a human-readable detail, or null. */
        void onResult(int result, String message);
    }

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile IKeylingHub hub;
    private boolean bound;

    private final IKeylingListener.Stub remoteListener = new IKeylingListener.Stub() {
        @Override
        public void onStateChanged(int state) {
            main.post(() -> listener.onStateChanged(state));
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            IKeylingHub h = IKeylingHub.Stub.asInterface(service);
            hub = h;
            try {
                h.registerListener(remoteListener);
            } catch (RemoteException ignored) {
                // onServiceDisconnected follows.
            }
            listener.onConnected();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            hub = null;
            listener.onDisconnected();
        }

        @Override
        public void onBindingDied(ComponentName name) {
            unbind();
            listener.onDisconnected();
        }
    };

    public KeylingClient(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public static boolean isHubInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo(Keyling.HUB_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** The hub's approval screen; start it for result. See {@link Keyling#ACTION_REQUEST_APPROVAL}. */
    public static Intent approvalIntent() {
        return new Intent(Keyling.ACTION_REQUEST_APPROVAL).setPackage(Keyling.HUB_PACKAGE);
    }

    /** Binds to the hub. Returns false if the hub is not installed. */
    public boolean bind() {
        if (bound) return true;
        Intent intent = new Intent(Keyling.ACTION_BIND).setPackage(Keyling.HUB_PACKAGE);
        bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        if (!bound) {
            try {
                context.unbindService(connection);
            } catch (IllegalArgumentException ignored) {
                // Some Android versions don't register a connection whose bind failed.
            }
        }
        return bound;
    }

    public void unbind() {
        if (!bound) return;
        IKeylingHub h = hub;
        if (h != null) {
            try {
                h.unregisterListener(remoteListener);
            } catch (RemoteException ignored) {
                // The hub is gone; nothing to unregister from.
            }
        }
        hub = null;
        bound = false;
        context.unbindService(connection);
    }

    public boolean isConnected() {
        return hub != null;
    }

    /** The hub's API version, or 0 if not connected. */
    public int getApiVersion() {
        IKeylingHub h = hub;
        if (h == null) return 0;
        try {
            return h.getApiVersion();
        } catch (RemoteException e) {
            return 0;
        }
    }

    /** One of Keyling.STATE_*. */
    public int getState() {
        IKeylingHub h = hub;
        if (h == null) return Keyling.STATE_UNKNOWN;
        try {
            return h.getState();
        } catch (RemoteException e) {
            return Keyling.STATE_UNKNOWN;
        }
    }

    /**
     * Types text on the computer. layout names a keyboard layout such as "en-US", or is null for
     * the hub's default. The callback gets the result once the dongle has queued the text.
     */
    public void typeText(String text, String layout, ResultCallback callback) {
        IKeylingHub h = hub;
        if (h == null) {
            post(callback, Keyling.RESULT_UNAVAILABLE, "not connected to the Keyling app");
            return;
        }
        try {
            h.typeText(text, layout, new IKeylingCallback.Stub() {
                @Override
                public void onResult(int result, String message) {
                    post(callback, result, message);
                }
            });
        } catch (RemoteException e) {
            post(callback, Keyling.RESULT_UNAVAILABLE, e.toString());
        }
    }

    /** Stops typing and drops input not typed yet. */
    public void abort() {
        IKeylingHub h = hub;
        if (h == null) return;
        try {
            h.abort();
        } catch (RemoteException ignored) {
            // Not connected, so nothing is typing on behalf of this app.
        }
    }

    /**
     * Binds, types text and unbinds. The callback runs exactly once. Keep the calling component
     * alive until it does (for a broadcast receiver, use goAsync()).
     */
    public static void typeOnce(Context context, String text, String layout, ResultCallback callback) {
        Handler main = new Handler(Looper.getMainLooper());
        final KeylingClient[] client = new KeylingClient[1];
        final boolean[] done = new boolean[1];
        ResultCallback finish = (result, message) -> {
            if (done[0]) return;
            done[0] = true;
            client[0].unbind();
            callback.onResult(result, message);
        };
        client[0] = new KeylingClient(context, new Listener() {
            @Override
            public void onConnected() {
                client[0].typeText(text, layout, finish);
            }

            @Override
            public void onDisconnected() {
                finish.onResult(Keyling.RESULT_UNAVAILABLE, "lost the connection to the Keyling app");
            }

            @Override
            public void onStateChanged(int state) {
            }
        });
        main.post(() -> {
            if (!client[0].bind()) {
                finish.onResult(Keyling.RESULT_UNAVAILABLE, "the Keyling app is not installed");
            }
        });
    }

    private void post(ResultCallback callback, int result, String message) {
        if (callback != null) main.post(() -> callback.onResult(result, message));
    }
}
