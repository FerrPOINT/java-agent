#!/usr/bin/env bash
# The initialized workspace owns Java Agent and its infrastructure.
set -euo pipefail
if [ "$#" -ne 0 ]; then
    echo 'Select the initialized workspace with SDLC_WORKSPACE_DIR and SDLC_PROJECT; argument overrides are not accepted.' >&2
    exit 2
fi
: "${SDLC_WORKSPACE_DIR:?Select the initialized workspace root}"
: "${SDLC_PROJECT:?Set sdlc1, sdlc2 or sdlc3}"
case "$SDLC_PROJECT" in sdlc1|sdlc2|sdlc3) ;; *) echo 'Unknown workspace project' >&2; exit 2 ;; esac
test -f "$SDLC_WORKSPACE_DIR/workspace.local.json"
test -f "$SDLC_WORKSPACE_DIR/workspace.ps1"
"${SDLC_PYTHON:-python3}" - "$SDLC_WORKSPACE_DIR" "$SDLC_PROJECT" <<'PY'
import json,sys
from pathlib import Path
root=Path(sys.argv[1]).resolve(strict=True)
profile=json.loads((root/"workspace.local.json").read_text(encoding="utf-8-sig"))
if not isinstance(profile,dict) or profile.get("project") != sys.argv[2]:
    raise SystemExit("Workspace profile differs from selected project")
if profile.get("initialized") is not True:
    raise SystemExit("Select an initialized workspace; this launcher never initializes storage")
runtime=profile.get("runtime")
if not isinstance(runtime,str) or not runtime:
    raise SystemExit("The initialized workspace runtime is missing")
target=(root/runtime).resolve(strict=True)
if not target.is_relative_to(root) or not target.is_file():
    raise SystemExit("The runtime must be a file inside the selected workspace")
PY
exec pwsh -NoProfile -File "$SDLC_WORKSPACE_DIR/workspace.ps1" Start -Service java-agent-backend
