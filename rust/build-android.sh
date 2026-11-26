#!/bin/sh
# Invoked by Gradle from rust/ so .cargo/config.toml is applied.
set -eu

cargo_command=$1
output_directory=$2
target_directory=$3
case "$cargo_command" in
    */*) PATH="$(dirname "$cargo_command"):$PATH"; export PATH ;;
esac
expected_version='cargo-ndk 4.1.2'
actual_version=$("$cargo_command" ndk --version)
if [ "$actual_version" != "$expected_version" ]; then
    printf 'Expected %s, got %s. Install: cargo install cargo-ndk --version 4.1.2 --locked\n' \
        "$expected_version" "$actual_version" >&2
    exit 1
fi

exec "$cargo_command" ndk --platform 28 \
    --target arm64-v8a --target armeabi-v7a --target x86 --target x86_64 \
    --output-dir "$output_directory" \
    build --release --locked --workspace --target-dir "$target_directory"
