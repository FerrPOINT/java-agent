#!/usr/bin/env bash
set -euo pipefail
repository=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
operation=${1:-}
module=${2:-all}
usage() {
    echo 'Usage: quality-make.sh build|test|jar|coverage backend|cli|bot|shared|all [--coverage] [--candidate-epoch]' >&2
    exit 64
}
[[ $# -ge 1 && $# -le 4 ]] || usage
case "$operation" in build|test|jar|coverage) ;; *) usage ;; esac
case "$module" in backend|cli|bot|shared|all) ;; *) usage ;; esac
include_coverage=false
candidate_epoch=false
for option in "${@:3}"; do
    case "$option" in
        --coverage) $include_coverage && usage; include_coverage=true ;;
        --candidate-epoch) $candidate_epoch && usage; candidate_epoch=true ;;
        *) usage ;;
    esac
done
if $candidate_epoch && [[ "$operation" != jar ]]; then usage; fi
[[ "$operation" != coverage ]] || include_coverage=true
if $include_coverage && [[ "$module" == cli || "$module" == shared ]]; then
    echo 'Coverage reports are declared only for backend and bot; select their scope or all.' >&2
    exit 64
fi

# Development uses a stable, explicitly unversioned epoch. A docs/test
# commit must not invalidate buildInfo and the JAR merely through HEAD time.
# Install/release explicitly request one candidate epoch before either wrapper;
# an operator-supplied epoch is always preserved and the existing helper checks it.
if [[ -z "${SOURCE_DATE_EPOCH:-}" ]]; then
    if $candidate_epoch; then
        SOURCE_DATE_EPOCH=$(git -C "$repository" show -s --format=%ct HEAD)
    else
        SOURCE_DATE_EPOCH=0
    fi
fi
export SOURCE_DATE_EPOCH
options=(--no-daemon --max-workers=2)
backend_tasks=()
root_tasks=()
if [[ "$module" == backend || "$module" == all ]]; then
    case "$operation" in
        build|test)
            # Reports already require the full tests; no separate wrapper start.
            if $include_coverage; then backend_tasks=(:jacocoTestReport); else backend_tasks=(:test); fi ;;
        jar)
            backend_tasks=(:bootJar) # bootJar already requires reviewed units.
            if $include_coverage; then backend_tasks+=(:jacocoTestReport); fi ;;
        coverage) backend_tasks=(:jacocoTestReport) ;;
    esac
fi
projects=()
case "$module" in
    all) projects=(telegram-bot cli shared) ;;
    bot) projects=(telegram-bot) ;;
    cli|shared) projects=("$module") ;;
esac
for project in "${projects[@]}"; do
    case "$operation" in
        build|test)
            if $include_coverage && [[ "$project" == telegram-bot ]]; then
                root_tasks+=(:telegram-bot:jacocoTestReport)
            else
                root_tasks+=(":$project:test")
            fi ;;
        jar)
            if [[ "$project" == shared ]]; then
                root_tasks+=(:shared:test)
                # Application bootJar dependencies already produce the library.
                [[ "$module" != shared ]] || root_tasks+=(:shared:jar)
            else
                root_tasks+=(":$project:bootJar")
                if $include_coverage && [[ "$project" == telegram-bot ]]; then
                    root_tasks+=(:telegram-bot:jacocoTestReport)
                fi
            fi ;;
        coverage)
            # CLI/shared do not declare JaCoCo report tasks.
            [[ "$project" != telegram-bot ]] || root_tasks+=(:telegram-bot:jacocoTestReport) ;;
    esac
done
if [[ ${#backend_tasks[@]} -gt 0 ]]; then
    bash "$repository/scripts/quality-gradle.sh" --backend "${backend_tasks[@]}" "${options[@]}"
fi
if [[ ${#root_tasks[@]} -gt 0 ]]; then
    root_graph=()
    # Reports/manual lanes and standalone Shared packaging keep their full graph.
    if ! $include_coverage && [[ "$operation" != jar || "$module" != shared ]]; then
        root_graph=(--quality-root)
    fi
    exec bash "$repository/scripts/quality-gradle.sh" "${root_graph[@]}" "${root_tasks[@]}" "${options[@]}"
fi
