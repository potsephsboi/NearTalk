#include <jni.h>
#include <array>
#include <cstddef>
#include <cstdint>
#include "mlkem_native.h"

// Stack buffers are wiped on every return path, including JNI errors.
template <std::size_t N> struct Buffer {
    std::array<uint8_t, N> data{};
    ~Buffer() {
        volatile uint8_t* p = data.data();
        for (std::size_t i = 0; i < N; ++i) p[i] = 0;
    }
};

static void fail(JNIEnv* env, const char* message) {
    jclass cls = env->FindClass("java/security/GeneralSecurityException");
    if (cls != nullptr) env->ThrowNew(cls, message);
}

template <std::size_t N>
static bool read(JNIEnv* env, jbyteArray input, Buffer<N>& output) {
    if (input == nullptr || env->GetArrayLength(input) != static_cast<jsize>(N)) {
        fail(env, "Invalid ML-KEM input length");
        return false;
    }
    env->GetByteArrayRegion(input, 0, N, reinterpret_cast<jbyte*>(output.data.data()));
    return !env->ExceptionCheck();
}

template <std::size_t N>
static jbyteArray bytes(JNIEnv* env, const Buffer<N>& input) {
    jbyteArray result = env->NewByteArray(N);
    if (result != nullptr) {
        env->SetByteArrayRegion(result, 0, N,
            reinterpret_cast<const jbyte*>(input.data.data()));
    }
    return result;
}

template <std::size_t A, std::size_t B>
static jobjectArray pair(JNIEnv* env, const Buffer<A>& first, const Buffer<B>& second) {
    jclass cls = env->FindClass("[B");
    if (cls == nullptr) return nullptr;
    jobjectArray result = env->NewObjectArray(2, cls, nullptr);
    if (result == nullptr) return nullptr;
    jbyteArray a = bytes(env, first);
    if (a == nullptr || env->ExceptionCheck()) return nullptr;
    env->SetObjectArrayElement(result, 0, a);
    if (env->ExceptionCheck()) return nullptr;
    jbyteArray b = bytes(env, second);
    if (b == nullptr || env->ExceptionCheck()) return nullptr;
    env->SetObjectArrayElement(result, 1, b);
    return result;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_example_neartalk_NativeKem_keyPair(JNIEnv* env, jobject, jbyteArray randomness) {
    Buffer<64> seed;
    Buffer<MLKEM768_PUBLICKEYBYTES> pk;
    Buffer<MLKEM768_SECRETKEYBYTES> sk;
    if (!read(env, randomness, seed)) return nullptr;
    if (neartalk_mlkem_keypair_derand(pk.data.data(), sk.data.data(), seed.data.data()) != 0) {
        fail(env, "ML-KEM key generation failed");
        return nullptr;
    }
    return pair(env, pk, sk);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_example_neartalk_NativeKem_encapsulate(
    JNIEnv* env, jobject, jbyteArray publicKey, jbyteArray randomness) {
    Buffer<MLKEM768_PUBLICKEYBYTES> pk;
    Buffer<32> seed;
    Buffer<MLKEM768_CIPHERTEXTBYTES> ct;
    Buffer<32> secret;
    if (!read(env, publicKey, pk) || !read(env, randomness, seed)) return nullptr;
    if (neartalk_mlkem_enc_derand(ct.data.data(), secret.data.data(),
                                pk.data.data(), seed.data.data()) != 0) {
        fail(env, "ML-KEM encapsulation failed");
        return nullptr;
    }
    return pair(env, ct, secret);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_example_neartalk_NativeKem_decapsulate(
    JNIEnv* env, jobject, jbyteArray privateKey, jbyteArray ciphertext) {
    Buffer<MLKEM768_SECRETKEYBYTES> sk;
    Buffer<MLKEM768_CIPHERTEXTBYTES> ct;
    Buffer<32> secret;
    if (!read(env, privateKey, sk) || !read(env, ciphertext, ct)) return nullptr;
    if (neartalk_mlkem_dec(secret.data.data(), ct.data.data(), sk.data.data()) != 0) {
        fail(env, "ML-KEM decapsulation failed");
        return nullptr;
    }
    return bytes(env, secret);
}
