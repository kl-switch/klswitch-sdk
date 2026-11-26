#!/usr/bin/env bash
#
# Build the WASM engine and sync it into all four wrappers.
#
# The only reason this exists rather than a bare `cargo build` is
# --remap-path-prefix. rustc embeds the absolute source path of every panic
# site as a plain string in the binary, so a default release build of this
# crate ships the builder's $CARGO_HOME inside four committed, published
# artifacts. The remaps rewrite those to /cargo and /src, which also makes the
# output identical from any checkout — anyone can rebuild and get the same
# bytes, which is the point of committing them at all.
#
# (`cargo`'s own `trim-paths` profile option would replace this, but it is
# still unstable as of cargo 1.95.)
set -euo pipefail

cd "$(dirname "$0")/.."

CARGO_HOME_DIR="${CARGO_HOME:-$HOME/.cargo}"
export RUSTFLAGS="--remap-path-prefix=${CARGO_HOME_DIR}=/cargo --remap-path-prefix=${PWD}/rust-core=/src ${RUSTFLAGS:-}"

cargo build --release --manifest-path rust-core/Cargo.toml --target wasm32-unknown-unknown

wasm="rust-core/target/wasm32-unknown-unknown/release/klswitch_core.wasm"

# Fail loudly rather than publish a leaky artifact. Matches the two prefixes
# the remaps above are meant to erase, as literals -- not $(whoami), which on a
# CI runner is a common word that could match legitimate engine strings.
if strings -a "$wasm" | grep -qF -e "$HOME" -e "$CARGO_HOME_DIR"; then
  echo "ERROR: build-machine paths survived in $wasm -- not copying." >&2
  strings -a "$wasm" | grep -F -e "$HOME" -e "$CARGO_HOME_DIR" | sort -u | head >&2
  exit 1
fi

cp "$wasm" sdk-node/wasm/klswitch_core.wasm
cp "$wasm" sdk-java/src/main/resources/klswitch_core.wasm
cp "$wasm" sdk-go/klswitch_core.wasm
cp "$wasm" sdk-python/src/klswitch/klswitch_core.wasm

echo "built $(wc -c <"$wasm") bytes; copied to sdk-node/wasm/, sdk-java/src/main/resources/, sdk-go/, sdk-python/src/klswitch/"
