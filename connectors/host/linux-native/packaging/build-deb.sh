#!/bin/sh
# Debian/Ubuntu/Raspberry Pi OS packaging glue; no Python at build or runtime.
# This packages only native C SAE J1979 tools; does not imply ECU qualification.
set -eu

cd "$(dirname "$0")/.."
binary=$1
if [ ! -f "$binary" ]; then
    echo "Native binary missing. Build with make before packaging." >&2
    exit 2
fi
command -v dpkg-deb >/dev/null 2>&1 || {
    echo "dpkg-deb (dpkg-dev) is required." >&2
    exit 2
}
command -v dpkg-shlibdeps >/dev/null 2>&1 || {
    echo "dpkg-shlibdeps (dpkg-dev) is required." >&2
    exit 2
}
arch=$(dpkg --print-architecture)
version=0.1.0
name=camarodiag-native-j1979
root=$(mktemp -d)
trap 'rm -rf "$root"' EXIT HUP INT TERM
mkdir -p "$root/DEBIAN" "$root/usr/bin" "$root/usr/share/doc/$name"
install -m 0755 "$binary" "$root/usr/bin/camarodiag-native"
install -m 0644 README.md "$root/usr/share/doc/$name/README.md"

# dpkg-shlibdeps derives ABI/SSL dependencies for the actual binary, so
# Debian 12, 13, Ubuntu and ARM family variants are not hardcoded.
deps=$(dpkg-shlibdeps -O -e"$root/usr/bin/camarodiag-native" |
    sed -n 's/^shlibs:Depends=//p')
if [ -z "$deps" ]; then
    echo "Could not resolve native shared-library dependencies." >&2
    exit 2
fi

cat > "$root/DEBIAN/control" <<EOF
Package: $name
Version: $version
Section: utils
Priority: optional
Architecture: $arch
Maintainer: CamaroDiag project
Depends: $deps
Description: Native C SAE J1979 CF/1 connector inspection tools
 Native Linux diagnostics, explicitly scoped to SAE J1979 Mode 02.
 Hardware communication requires qualified connector and operator approval.
 This package does not install Python, pyserial or a background daemon.
EOF

mkdir -p out
output="out/$name"_"$version"_"$arch.deb"
if [ -e "$output" ]; then
    echo "Package exists; refusing to overwrite: $output" >&2
    exit 2
fi
dpkg-deb --build --root-owner-group "$root" "$output"
echo "Staged native C package: $output (not a published Release)"
