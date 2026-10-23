# mlkem-native

Upstream: https://github.com/pq-code-package/mlkem-native
Release: v2.0.0
Commit: d1b2fe782888bdb761a50336012923180be7f502
Downloaded source archive SHA-256: 0b30fa186938f93037dfda6e3f1e1ca51b8614a762775d9ac9dbe1be72708c4c

This directory contains the unmodified portable C implementation and public
header from upstream's `mlkem/` directory, excluding the optional assembly
backends and upstream configuration file. NearTalk's configuration is
`../neartalk_config.h`: ML-KEM-768, portable C, deterministic API. Each API
invocation receives fresh randomness from Java's SecureRandom in SecureSession.

The C/C++ API wrapper is `../kem_jni.cpp`. No cryptographic algorithm is
reimplemented in that wrapper. HKDF and AES-GCM use Java's crypto providers.

Source headers retain upstream attribution. The implementation is distributed
under Apache-2.0 (see LICENSE); the license is also packaged in Android assets.
Upstream verification scope and limitations:
https://github.com/pq-code-package/mlkem-native/blob/d1b2fe782888bdb761a50336012923180be7f502/SOUNDNESS.md
