# NearTalk

This is an Android application that lets you talk to nearby devices directly, using Wi-Fi Direct.

You can either broadcast your messages as far as Wi-Fi reaches, or request a 1–1 conversation, encrypted with post-quantum cryptography algorithms (of course).

## Requirements

- Android phones with Wi-Fi Direct support.
- Wi-Fi and Location enabled.


Wi-Fi permissions are needed, plus Location permission on Android 8–12 or Nearby Wi-Fi devices permission on Android 13+. The app does not read GPS coordinates.

No internet connection, router, account, or central server is needed.


## Build

Open the project in Android Studio. Install NDK **30.0.16248370** and CMake **3.22.1** through the SDK Manager, sync Gradle, and run the app on both phones. The native library builds for 32-bit and 64-bit ARM.

## Some technical notes

- **Cryptography at 1-1 conversations:** ML-KEM-768 establishes a shared secret, HKDF-SHA-256 derives separate sending and receiving keys, and AES-256-GCM encrypts and authenticates the messages. SHA-256 hashes the handshake transcript. ML-KEM uses [mlkem-native](https://github.com/pq-code-package/mlkem-native) through a C++ JNI wrapper; the other algorithms use Java's cryptography APIs. The handshake happens automatically after accepting a request.
- **Broadcast delay:** Broadcast messages are carried in [Wi-Fi Direct service-discovery records](https://developer.android.com/develop/connectivity/wifi/nsd-wifi-direct). Other phones receive them when discovery finds those records. NearTalk restarts discovery every 15 seconds, and Android controls the radio scheduling, so delivery can take some seconds. However, messages are almost always delivered correctly.
- **1–1 conversations:** Once connected, messages travel over a TCP socket directly between the phones. They do not wait for service discovery, so the broadcast discovery delay does not apply.
- **Privacy:** Broadcast messages are public and unencrypted. The 1–1 connection is encrypted, but peer identity is not verified; the current protocol does not prevent an active man-in-the-middle attack.
- **Current limits:** Broadcast messages allow 180 UTF-8 bytes, and 1–1 messages allow 4096. Only the last three outgoing broadcasts remain advertised. One 1–1 connection is supported at a time, and discovery pauses during it. Messages are kept in memory; leaving the app closes the connection.
