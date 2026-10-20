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
quality_root=false
dependencies_only=false
while (($#)); do
    case "$1" in
        --backend)
            if $backend_graph || $quality_root; then echo 'Choose one Gradle graph' >&2; exit 64; fi
            directory=$repository/backend
            qualified_wrappers=(9.8.0 9.8.1)
            backend_graph=true ;;
        --quality-root)
            if $backend_graph || $quality_root; then echo 'Choose one Gradle graph' >&2; exit 64; fi
            quality_root=true ;;
        --dependencies-only)
            if $dependencies_only; then echo 'Duplicate dependency preparation mode' >&2; exit 64; fi
            dependencies_only=true ;;
        *) break ;;
    esac
    shift
done
if $dependencies_only; then
    if ! $backend_graph && ! $quality_root; then echo 'Dependency preparation needs an explicit graph' >&2; exit 64; fi
    dependency_tasks=0
    seen_tasks=' '
    for argument in "$@"; do
        case "$argument" in
            :qualityDependencyInputs)
                $backend_graph || { echo 'Dependency task is outside the selected graph' >&2; exit 64; } ;;
            :shared:qualityDependencyInputs) ;;
            :cli:qualityDependencyInputs|:telegram-bot:qualityDependencyInputs)
                $quality_root || { echo 'Dependency task is outside the selected graph' >&2; exit 64; } ;;
            --offline|--no-daemon|--build-cache|--no-build-cache|--max-workers=2|--console=plain|--configuration-cache|--configuration-cache-problems=fail) continue ;;
            *) echo 'Dependency preparation accepts only bounded snapshot arguments' >&2; exit 64 ;;
        esac
        if [[ "$seen_tasks" == *" $argument "* ]]; then echo 'Duplicate dependency snapshot task' >&2; exit 64; fi
        seen_tasks+="$argument "
        dependency_tasks=$((dependency_tasks + 1))
    done
    ((dependency_tasks > 0)) || { echo 'Choose explicit dependency snapshot tasks' >&2; exit 64; }
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
if $quality_root && ! $dependencies_only && { ! $qualified_graph || ! $selected_task; }; then
    echo 'Selected quality root accepts only bounded ordinary tasks and options' >&2
    exit 64
fi
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
if $quality_root; then set -- "$@" -PqualityRootGraph=clients-v1; fi
if $dependencies_only; then set -- "$@" -PqualityDependenciesOnly=true; fi
exec bash ./gradlew "$@"
