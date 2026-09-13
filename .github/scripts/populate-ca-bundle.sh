#!/usr/bin/env bash
# Populate mihomo's embedded Root CA bundle before the Go core is compiled.
#
# mihomo embeds component/ca/ca-certificates.crt via //go:embed and appends it
# to x509.SystemCertPool() (component/ca/config.go). The file is committed
# EMPTY upstream; mihomo's own CI copies the Debian bundle into it right before
# `go build`. Without this step libclash.so trusts only the Android system
# roots, and some HTTPS subscription/provider URLs fail with
# "x509: certificate signed by unknown authority".
#
# Must run AFTER the submodule checkout and BEFORE any gradle task that
# compiles the native core (assemble*, externalGolangBuild*).
set -euo pipefail

DEST="core/src/foss/golang/clash/component/ca/ca-certificates.crt"
SRC="/etc/ssl/certs/ca-certificates.crt"

if [ ! -d "$(dirname "$DEST")" ]; then
  echo "mihomo submodule is not checked out (missing $(dirname "$DEST"))" >&2
  exit 1
fi

sudo apt-get update -qq
sudo apt-get install -y -qq ca-certificates
sudo update-ca-certificates >/dev/null

test -s "$SRC" || { echo "system CA bundle $SRC is missing or empty" >&2; exit 1; }
cp -f "$SRC" "$DEST"

# Sanity: the embedded bundle must be non-empty and look like a real bundle.
test -s "$DEST"
ls -lh "$DEST"
COUNT="$(grep -c 'BEGIN CERTIFICATE' "$DEST")"
echo "embedded CA bundle: $COUNT certificates"
if [ "$COUNT" -lt 100 ]; then
  echo "CA bundle looks truncated ($COUNT certificates)" >&2
  exit 1
fi
