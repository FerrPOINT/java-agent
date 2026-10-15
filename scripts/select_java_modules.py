"""Map canonical Base change selection to the two compatible Gradle graphs."""

import importlib.util
import os
from pathlib import Path


def groups(components):
    selected = set(components)
    return {
        "java_backend": bool(selected & {"backend", "shared"}),
        "java_root": bool(selected & {"bot", "cli", "shared"}),
    }


def main():
    root = Path(__file__).resolve().parents[1]
    spec = importlib.util.spec_from_file_location("base_quality_selection", root / "scripts/vendor/base_quality_selection.py")
    selector = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(selector)
    event, base, head = (os.environ.get(name, "") for name in ("EVENT_NAME", "BASE_SHA", "HEAD_SHA"))
    values = groups(selector.ci_components(root, event, base, head))
    values["backend_build"] = values["java_backend"] and selector.ci_outputs(event, base, head, repo=root)["backend_build"] == "true"
    output = "".join(name + "=" + str(value).lower() + "\n" for name, value in values.items())
    if os.environ.get("GITHUB_OUTPUT"):
        with Path(os.environ["GITHUB_OUTPUT"]).open("a", encoding="utf-8") as stream:
            stream.write(output)
    else:
        print(output, end="")


if __name__ == "__main__":
    main()
