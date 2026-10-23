package com.example.neartalk

internal object NativeKem {
    init {
        System.loadLibrary("neartalk_crypto")
    }

    // Results: [public key, private key] and [ciphertext, shared secret].
    external fun keyPair(randomness: ByteArray): Array<ByteArray>
    external fun encapsulate(publicKey: ByteArray, randomness: ByteArray): Array<ByteArray>
    external fun decapsulate(privateKey: ByteArray, ciphertext: ByteArray): ByteArray
}
