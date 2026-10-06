#!/usr/bin/env bash
# Build, install, and verify the host systemd development runtime.
set -euo pipefail

ROOT="${JAVA_AGENT_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
INSTALL_DIR="${INSTALL_DIR:-/opt/java-agent}"
SYSTEMCTL="${SYSTEMCTL:-systemctl}"
CURL="${CURL:-curl}"
RELEASE_ID="${DEPLOY_RELEASE_ID:-$(git -C "$ROOT" rev-parse --short HEAD)-$(date -u +%Y%m%d%H%M%S)}"
SKIP_BUILD=false

usage() {
    echo "Usage: $0 [--skip-build]" >&2
}

if [ "${1:-}" = "--skip-build" ]; then
    SKIP_BUILD=true
elif [ "$#" -ne 0 ]; then
    usage
    exit 2
fi

if [ "$SKIP_BUILD" = false ]; then
    (cd "$ROOT" && ./gradlew :backend:bootJar :telegram-bot:bootJar --no-daemon)
fi

backend_jar="$ROOT/backend/build/libs/backend-0.0.1-SNAPSHOT.jar"
bot_jar="$ROOT/telegram-bot/build/libs/telegram-bot-0.0.1-SNAPSHOT.jar"
for artifact in "$backend_jar" "$bot_jar"; do
    if [ ! -f "$artifact" ]; then
        echo "Missing build artifact: $artifact" >&2
        exit 1
    fi
done

releases_dir="$INSTALL_DIR/releases"
release_dir="$releases_dir/$RELEASE_ID"
lib_dir="$INSTALL_DIR/lib"
mkdir -p "$releases_dir" "$lib_dir"
if [ -e "$release_dir" ]; then
    echo "Release directory already exists: $release_dir" >&2
    exit 1
fi

staging_dir="$(mktemp -d "$releases_dir/.${RELEASE_ID}.XXXXXX")"
cleanup() {
    rm -rf "$staging_dir"
}
trap cleanup EXIT

cp "$backend_jar" "$staging_dir/backend.jar"
cp "$bot_jar" "$staging_dir/telegram-bot.jar"
printf '%s\n' "$RELEASE_ID" > "$staging_dir/VERSION"
mv "$staging_dir" "$release_dir"
trap - EXIT

# Publish all artifacts before either unit restarts, so both services use one release.
ln -s "$release_dir" "$INSTALL_DIR/.latest.new"
mv -Tf "$INSTALL_DIR/.latest.new" "$INSTALL_DIR/latest"
for component in backend bot; do
    artifact="backend.jar"
    [ "$component" = "bot" ] && artifact="telegram-bot.jar"
    link="$lib_dir/java-agent-$component-latest.jar"
    ln -s "$release_dir/$artifact" "$lib_dir/.java-agent-$component-latest.jar.new"
    mv -Tf "$lib_dir/.java-agent-$component-latest.jar.new" "$link"
done

"$SYSTEMCTL" restart java-agent-backend.service
for _ in $(seq 1 30); do
    if "$CURL" -fsS --max-time 3 http://127.0.0.1:8090/actuator/health/readiness >/dev/null; then
        break
    fi
    sleep 2
done
"$CURL" -fsS --max-time 5 http://127.0.0.1:8090/actuator/health/readiness >/dev/null

"$SYSTEMCTL" restart java-agent-bot.service
for _ in $(seq 1 30); do
    if "$CURL" -fsS --max-time 3 http://127.0.0.1:8091/actuator/health >/dev/null; then
        break
    fi
    sleep 2
done
"$CURL" -fsS --max-time 5 http://127.0.0.1:8091/actuator/health >/dev/null
"$SYSTEMCTL" is-active java-agent-backend.service java-agent-bot.service >/dev/null

for path in "$INSTALL_DIR/latest" "$lib_dir/java-agent-backend-latest.jar" "$lib_dir/java-agent-bot-latest.jar"; do
    case "$(readlink -f "$path")" in
        "$release_dir"|"$release_dir"/*) ;;
        *)
            echo "Deployment link does not resolve to $release_dir: $path" >&2
            exit 1
            ;;
    esac
done

printf 'Deployed release: %s\n' "$release_dir"
