#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
test_build_dir=$(mktemp -d)
trap 'rm -rf "$test_build_dir"' EXIT

cmake -S app/src/main/cpp -B "$test_build_dir/native" -DCMAKE_BUILD_TYPE=Release
cmake --build "$test_build_dir/native" -j 2
kotlinc app/src/main/java/com/example/neartalk/NativeKem.kt \
    app/src/main/java/com/example/neartalk/SecureSession.kt \
    tests/unicast/SecureSessionTest.kt -include-runtime -d "$test_build_dir/tests.jar"
java -Djava.library.path="$test_build_dir/native" -jar "$test_build_dir/tests.jar" \
    tests/unicast/mlkem768.properties
