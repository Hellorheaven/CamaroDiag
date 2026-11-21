#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
build="$(mktemp -d)"
trap 'rm -rf "$build"' EXIT
cmake -S "$root" -B "$build/host" -DCMAKE_BUILD_TYPE=Debug
cmake --build "$build/host" --parallel
ctest --test-dir "$build/host" --output-on-failure

# Cross-compile the actual MMIO component and its freestanding dependencies.
# These are relocatable PowerPC objects only: no board startup, linker image,
# upload binary or hardware compatibility is implied by this test.
for source in flash_map flash_controller kernel_protocol isotp_rx isotp_tx flash_mmio; do
    clang --target=powerpc-none-eabi -mcpu=powerpc -std=c11 -Os \
        -ffreestanding -fno-builtin -Wall -Wextra -Werror \
        -c "$root/$source.c" -o "$build/$source.o"
    file "$build/$source.o"
done
