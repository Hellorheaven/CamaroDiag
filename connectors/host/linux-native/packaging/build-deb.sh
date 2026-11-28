#!/bin/sh
# Debian/Ubuntu/Raspberry Pi OS packaging glue; no Python at build or runtime.
# Native C++20 SAE J1979 tools; optional Qt desktop; no ECU qualification implied.
set -eu

cd "$(dirname "$0")/.."
binary=$1
desktop=${2-}
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
if [ -n "$desktop" ]; then
    test -f "$desktop" || { echo "Native desktop missing." >&2; exit 2; }
    name=camarodiag-native-j1979-desktop
fi
work=$(mktemp -d)
root="$work/package"
trap 'rm -rf "$work"' EXIT HUP INT TERM
mkdir -p "$root/DEBIAN" "$root/usr/bin" "$root/usr/share/doc/$name"
install -m 0755 "$binary" "$root/usr/bin/camarodiag-native"
install -m 0644 README.md "$root/usr/share/doc/$name/README.md"
if [ -n "$desktop" ]; then
    install -m 0755 "$desktop" "$root/usr/bin/camarodiag-j1979-desktop"
    mkdir -p "$root/usr/share/applications"
    install -m 0644 packaging/camarodiag-j1979.desktop "$root/usr/share/applications/"
fi

# dpkg-shlibdeps derives ABI/SSL dependencies for the actual binary, so
# Debian 12, 13, Ubuntu and ARM family variants are not hardcoded.
set -- -O -e"$root/usr/bin/camarodiag-native"
if [ -n "$desktop" ]; then set -- "$@" -e"$root/usr/bin/camarodiag-j1979-desktop"; fi
mkdir -p "$work/debian"
cat > "$work/debian/control" <<EOF
Source: $name
Section: utils
Priority: optional
Maintainer: CamaroDiag project
Standards-Version: 4.6.2

Package: $name
Architecture: any
Description: Native CamaroDiag J1979
EOF
deps=$(cd "$work" && dpkg-shlibdeps "$@" | sed -n 's/^shlibs:Depends=//p')
if [ -z "$deps" ]; then
    echo "Could not resolve native shared-library dependencies." >&2
    exit 2
fi

other=camarodiag-native-j1979-desktop
if [ -n "$desktop" ]; then other=camarodiag-native-j1979; fi
cat > "$root/DEBIAN/control" <<EOF
Package: $name
Version: $version
Section: utils
Priority: optional
Architecture: $arch
Maintainer: CamaroDiag project
Depends: $deps
Conflicts: $other
Replaces: $other
Description: Native C++20 SAE J1979 connector and evidence inspection tools
 Native Linux diagnostics, explicitly scoped to SAE J1979.
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
echo "Staged native C++20 package: $output (not a published Release)"
