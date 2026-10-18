#!/usr/bin/env bash
set -euo pipefail
repository=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
operation=${1:-}
module=${2:-all}
usage() {
    echo 'Usage: quality-make.sh build|test|jar|coverage backend|cli|bot|shared|all' >&2
    exit 64
}
[[ $# -ge 1 && $# -le 2 ]] || usage
case "$operation" in build|test|jar|coverage) ;; *) usage ;; esac
case "$module" in backend|cli|bot|shared|all) ;; *) usage ;; esac
if [[ "$operation" == coverage && ( "$module" == cli || "$module" == shared ) ]]; then
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
        build|test) backend_tasks=(:test) ;;
        jar) backend_tasks=(:bootJar) ;; # bootJar already requires reviewed units.
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
        build|test) root_tasks+=(":$project:test") ;;
        jar)
            if [[ "$project" == shared ]]; then
                root_tasks+=(:shared:test)
                # Application bootJar dependencies already produce the library.
                [[ "$module" != shared ]] || root_tasks+=(:shared:jar)
            else
                root_tasks+=(":$project:bootJar")
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
