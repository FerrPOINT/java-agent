#!/bin/sh
# Check the actual pushed commit tips, including refs other than checked-out HEAD.
set -eu
while read -r local_ref local_sha remote_ref remote_sha; do
    if [ -z "$local_ref" ] || [ -z "$remote_sha" ]; then
        echo 'Repository hygiene: invalid pre-push input' >&2
        exit 2
    fi
    case "$local_sha" in
        *[!0]*) "${PYTHON:-python3}" -B scripts/check_repository_hygiene.py --ref "$local_sha" ;;
        *) : ;; # A deleted ref has no snapshot to check.
    esac
done
