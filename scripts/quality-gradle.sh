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
base_compile_cache=false
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
        --base-compile-cache)
            if $base_compile_cache; then echo 'Duplicate Base compiler cache mode' >&2; exit 64; fi
            base_compile_cache=true ;;
        *) break ;;
    esac
    shift
done
cache_environment=false
for cache_variable in BASE_JAVA_COMPILE_CACHE_ACTIVE BASE_JAVA_COMPILE_CACHE_PROFILE \
    BASE_JAVA_COMPILE_CACHE_NAMESPACE BASE_JAVA_COMPILE_CACHE_POLICY_SHA256 BASE_JAVA_COMPILE_CACHE_POLICY; do
    if [[ -v "$cache_variable" ]]; then cache_environment=true; fi
done
if $base_compile_cache; then
    if ! $quality_root || $backend_graph || $dependencies_only \
        || [[ "${BASE_JAVA_COMPILE_CACHE_ACTIVE:-}" != 1 \
            || "${BASE_JAVA_COMPILE_CACHE_PROFILE:-}" != root-clients-v1 \
            || ! "${BASE_JAVA_COMPILE_CACHE_NAMESPACE:-}" =~ ^[a-f0-9]{64}$ \
            || ! "${BASE_JAVA_COMPILE_CACHE_POLICY_SHA256:-}" =~ ^[a-f0-9]{64}$ ]]; then
        echo 'Base compiler cache requires its explicit supported graph and environment' >&2; exit 64
    fi
    for argument in "$@"; do
        case "$argument" in
            :cli:test|:shared:test|:cli:bootJar|--offline|--no-daemon|--build-cache|--max-workers=2|--console=plain) ;;
            *) echo 'Base compiler cache accepts only bounded CLI/Shared tasks and options' >&2; exit 64 ;;
        esac
    done
    cache_policy=${BASE_JAVA_COMPILE_CACHE_POLICY:-}
    if [[ "$cache_policy" != /* || "${cache_policy##*/}" != java-qa-compile-cache.init.gradle \
        || "$cache_policy" == *$'\n'* || "$cache_policy" == *$'\r'* \
        || ! -f "$cache_policy" || -L "$cache_policy" \
        || "$(readlink -e -- "$cache_policy")" != "$cache_policy" ]]; then
        echo 'Base compiler cache policy must be an absolute plain file' >&2; exit 64
    fi
    cache_policy_size=$(wc -c < "$cache_policy")
    if ((cache_policy_size > 65536)); then echo 'Base compiler cache policy exceeds its byte budget' >&2; exit 64; fi
    cache_policy_hash=$(sha256sum -- "$cache_policy") || { echo 'Base compiler cache policy is unavailable' >&2; exit 64; }
    if [[ "${cache_policy_hash%% *}" != "$BASE_JAVA_COMPILE_CACHE_POLICY_SHA256" ]]; then
        echo 'Base compiler cache policy bytes changed' >&2; exit 64
    fi
elif $cache_environment; then
    echo 'Base compiler cache environment requires the explicit helper switch' >&2; exit 64
fi
for argument in "$@"; do
    if [[ "$argument" == --base-compile-cache ]]; then
        echo 'Base compiler cache switch must precede task arguments' >&2; exit 64
    fi
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
if $quality_root; then
    # Task selection and Settings share the product dependency closure. Do not
    # configure sibling clients merely to execute one selected application.
    root_cli=false; root_bot=false; root_shared=false
    for argument in "$@"; do
        case "$argument" in
            :cli:test|:cli:bootJar|:cli:qualityDependencyInputs) root_cli=true ;;
            :telegram-bot:test|:telegram-bot:bootJar|:telegram-bot:qualityDependencyInputs) root_bot=true ;;
            :shared:test|:shared:qualityDependencyInputs) root_shared=true ;;
        esac
    done
    root_components=''
    if $root_bot; then root_components=bot; fi
    if $root_cli; then root_components+=${root_components:+,}cli; fi
    if $root_shared; then root_components+=${root_components:+,}shared; fi
    [[ -n "$root_components" ]] || { echo 'Choose selected root components' >&2; exit 64; }
    set -- "$@" -PqualityRootGraph=clients-selected-v1 "-PqualityRootComponents=$root_components"
fi
if $dependencies_only; then set -- "$@" -PqualityDependenciesOnly=true; fi
if $base_compile_cache; then set -- "$@" --init-script "$cache_policy" --build-cache; fi
exec bash ./gradlew "$@"
