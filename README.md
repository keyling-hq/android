# Keyling for Android

The Android side of [Keyling](https://github.com/keyling-hq/firmware): a USB dongle that plugs
into a PC as a keyboard and types what a phone tells it over Bluetooth LE.

This repository holds:

- **`hub/`**, the Keyling app. It pairs with the dongle, keeps the Bluetooth connection in a
  foreground service, turns text into keystrokes with phone-side keyboard layouts, and offers an
  API that other apps (password managers, for example) use to type through the dongle. Apps must
  be approved by the user in the hub before they can type.
- **`client/`**, the small library other apps link to talk to the hub. It contains no Bluetooth or
  protocol code.
- **`testclient/`**, a small app that exercises the client library: binding, approval, typing
  and state changes. It's for development and doubles as sample code.
- **`proto/`**, a Git submodule with
  [keyling-hq/protocol](https://github.com/keyling-hq/protocol), the phone ↔ dongle protocol
  (`bridge.proto`). The hub generates its protocol classes from it at build time.

The hub requires Android 12 or newer and a phone with Bluetooth LE.

## Building from a fresh clone

### Requirements

- **Git**.
- **JDK 17 or newer** (tested with JDK 21). `java` must be on `PATH`, or `JAVA_HOME` must point to
  the JDK.
- **Android SDK command-line tools** (`cmdline-tools`), with the SDK licenses accepted.
- **Android SDK platform-tools** (`adb`), to install the app on a phone.

Android Studio is not required; it bundles all of the above if you prefer it.

You do not need to install Gradle, the SDK platform or the build tools: the Gradle wrapper
(`gradlew`) downloads Gradle, and the first build downloads Android SDK platform 37 and its build
tools (a few hundred MB) once the licenses are accepted.

### 1. Set up the Android SDK

If you don't have the SDK yet, download the "command line tools only" package from
<https://developer.android.com/studio#command-line-tools-only> and unpack it so that the tools end
up in `<sdk>/cmdline-tools/latest/`. Then:

```sh
sdkmanager --licenses          # accept all licenses
sdkmanager "platform-tools"
```

Tell Gradle where the SDK is, either with the `ANDROID_HOME` environment variable or with a
`local.properties` file in the repository root (not committed):

```properties
sdk.dir=C\:\\Users\\you\\Android\\Sdk
```

### 2. Clone with the submodule

The protocol definitions live in a submodule, which a plain `git clone` leaves empty. Clone with:

```sh
git clone --recurse-submodules https://github.com/keyling-hq/android.git
```

If you already cloned without it, run this inside the repository:

```sh
git submodule update --init
```

The build stops with an error if `proto/` is empty.

The submodule is pinned to a specific protocol commit. When a `git pull` moves that pin, update
your checkout with `git submodule update`, or make Git do it on every pull:

```sh
git config submodule.recurse true
```

### 3. Build and install

On Windows use `gradlew.bat`, elsewhere `./gradlew`:

```sh
gradlew.bat :hub:assembleDebug     # APK in hub/build/outputs/apk/debug/
gradlew.bat :hub:installDebug      # build and install on a connected phone (USB debugging on)
gradlew.bat :testclient:installDebug   # the API test client
gradlew.bat test                   # unit tests
```

The dongle can't be reached from the Android emulator (it has no Bluetooth LE), so testing the
app end to end needs a real phone.

## Updating the protocol

To move to the latest protocol version (maintainers):

```sh
git submodule update --remote proto
git add proto
git commit -m "chore: update protocol"
```

The pinned submodule commit records which protocol version the app speaks. The `.proto` file is
never copied into this repository.

## License

- The hub app (`hub/`) is licensed under GPL-2.0-or-later; see [LICENSE](LICENSE).
- The client library (`client/`) is licensed under MIT; see [client/LICENSE](client/LICENSE), so
  that apps under any license can integrate with the hub.
- The protocol (`proto/`) is licensed under MIT; see its own repository.
