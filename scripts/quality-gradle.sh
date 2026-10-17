#!/usr/bin/env bash
set -euo pipefail
repository=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
if [[ -z "${SOURCE_DATE_EPOCH:-}" ]]; then
    SOURCE_DATE_EPOCH=$(git -C "$repository" show -s --format=%ct HEAD)
fi
[[ "$SOURCE_DATE_EPOCH" =~ ^[0-9]+$ ]] || { echo 'SOURCE_DATE_EPOCH must be candidate Unix seconds' >&2; exit 64; }
export SOURCE_DATE_EPOCH
directory=$repository
qualified_wrappers=(9.6.1)
backend_graph=false
if [[ "${1:-}" == --backend ]]; then
    directory=$repository/backend
    qualified_wrappers=(9.8.0 9.8.1)
    backend_graph=true
    shift
fi
cd -- "$directory"

# Only the native-qualified ordinary graphs receive a default. Init scripts,
# release/DB tasks, filtered tests and explicit operator options pass through.
qualified_graph=true
selected_task=false
for argument in "$@"; do
    case "$argument" in
        :shared:test) selected_task=true ;;
        test|:test|bootJar|:bootJar)
            if $backend_graph; then selected_task=true; else qualified_graph=false; fi ;;
        :cli:test|:cli:bootJar|:telegram-bot:test|:telegram-bot:bootJar)
            if $backend_graph; then qualified_graph=false; else selected_task=true; fi ;;
        --offline|--no-daemon|--build-cache|--no-build-cache|--max-workers=2|--console=plain) ;;
        *) qualified_graph=false ;;
    esac
done
wrapper=gradle/wrapper/gradle-wrapper.properties
jdk_release=${JAVA_HOME:-}/release
property_value() {
    local key value found=''
    while IFS='=' read -r key value; do
        if [[ "$key" == "$2" ]]; then found=${value%$'\r'}; fi
    done < "$1"
    printf '%s' "$found"
}
matches_wrapper() {
    local actual version
    actual=$(property_value "$wrapper" distributionUrl)
    for version in "${qualified_wrappers[@]}"; do
        if [[ "$actual" == "https\\://services.gradle.org/distributions/gradle-${version}-bin.zip" ]]; then
            return 0
        fi
    done
    return 1
}
if $qualified_graph && $selected_task && [[ -f "$wrapper" && -f "$jdk_release" ]] \
    && matches_wrapper \
    && [[ "$(property_value "$jdk_release" JAVA_VERSION)" == '"25.0.4.1"' ]] \
    && [[ "$(property_value "$jdk_release" IMPLEMENTOR)" == '"Eclipse Adoptium"' ]] \
    && [[ "$(property_value "$jdk_release" OS_NAME)" == '"Linux"' ]] \
    && [[ "$(property_value "$jdk_release" OS_ARCH)" == '"x86_64"' ]]; then
    set -- "$@" --configuration-cache --configuration-cache-problems=fail
fi
exec bash ./gradlew "$@"
