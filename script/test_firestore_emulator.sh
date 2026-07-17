#!/usr/bin/env bash
set -euo pipefail

emulator_host="127.0.0.1:${FIRESTORE_EMULATOR_PORT:-8787}"
scratch="$(mktemp -d)"
emulator_pid=""

cleanup() {
  if [ -n "$emulator_pid" ]; then
    kill "$emulator_pid" 2>/dev/null || true
    wait "$emulator_pid" 2>/dev/null || true
  fi
  rm -r "$scratch"
}
trap cleanup EXIT

gcloud emulators firestore start \
  --host-port="$emulator_host" \
  --quiet >"$scratch/firestore.log" 2>&1 &
emulator_pid="$!"

for _ in $(seq 1 60); do
  if curl --silent --fail "http://$emulator_host/" >/dev/null 2>&1; then
    FIRESTORE_EMULATOR_HOST="$emulator_host" clojure -M:test
    exit 0
  fi
  if ! kill -0 "$emulator_pid" 2>/dev/null; then
    cat "$scratch/firestore.log" >&2
    exit 1
  fi
  sleep 0.25
done

cat "$scratch/firestore.log" >&2
echo "Firestore emulator did not become ready" >&2
exit 1
