#!/usr/bin/env bash
set -euo pipefail
repository=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
operation=${1:-}
module=${2:-all}
usage() {
    echo 'Usage: quality-make.sh build|test|jar|coverage backend|cli|bot|shared|all [--coverage]' >&2
    exit 64
}
[[ $# -ge 1 && $# -le 3 ]] || usage
case "$operation" in build|test|jar|coverage) ;; *) usage ;; esac
case "$module" in backend|cli|bot|shared|all) ;; *) usage ;; esac
include_coverage=false
if [[ $# == 3 ]]; then
    [[ "$3" == --coverage ]] || usage
    include_coverage=true
fi
[[ "$operation" != coverage ]] || include_coverage=true
if $include_coverage && [[ "$module" == cli || "$module" == shared ]]; then
    echo 'Coverage reports are declared only for backend and bot; select their scope or all.' >&2
    exit 64
fi

# Freeze one candidate epoch for both compatible graphs. The existing helper
# validates it and retains ownership of wrapper/configuration-cache policy.
if [[ -z "${SOURCE_DATE_EPOCH:-}" ]]; then
    SOURCE_DATE_EPOCH=$(git -C "$repository" show -s --format=%ct HEAD)
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
    exec bash "$repository/scripts/quality-gradle.sh" "${root_tasks[@]}" "${options[@]}"
fi
