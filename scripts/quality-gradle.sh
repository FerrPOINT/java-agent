#!/usr/bin/env bash
set -euo pipefail
repository=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
if [[ -z "${SOURCE_DATE_EPOCH:-}" ]]; then
    SOURCE_DATE_EPOCH=$(git -C "$repository" show -s --format=%ct HEAD)
fi
[[ "$SOURCE_DATE_EPOCH" =~ ^[0-9]+$ ]] || { echo 'SOURCE_DATE_EPOCH must be candidate Unix seconds' >&2; exit 64; }
export SOURCE_DATE_EPOCH
directory=$repository
if [[ "${1:-}" == --backend ]]; then
    directory=$repository/backend
    shift
fi
cd -- "$directory"
exec bash ./gradlew "$@"
