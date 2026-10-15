"""Validate the reviewed inventory used by native Gradle unit/boundary tasks."""

from pathlib import Path
import json
import re

ROOT = Path(__file__).resolve().parents[1]


def validate(root=ROOT):
    inventory = json.loads(
        (root / "gradle/test-lanes.json").read_text(encoding="utf-8")
    )
    files = inventory["files"]
    actual = set()
    for module in ("backend", "telegram-bot", "cli", "shared"):
        for path in (root / module / "src/test").rglob("*"):
            if path.suffix not in (".java", ".groovy"):
                continue
            text = path.read_text(encoding="utf-8")
            if not re.search(
                r"@(Test\b|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)|extends\s+Specification",
                text,
            ):
                continue
            relative = path.relative_to(root).as_posix()
            actual.add(relative)
            if relative not in files:
                raise ValueError("Classify new test before build: " + relative)
            if files[relative] not in ("unit", "integration", "slow", "live"):
                raise ValueError("Unknown test tier: " + relative)
            if files[relative] == "unit" and re.search(
                r"@(SpringBootTest|WebMvcTest|DataJpaTest|ContextConfiguration)|SpringExtension|"
                r"Testcontainers|PostgresTestContainer|DisposablePostgres|ServerSocket|HttpServer|"
                r"MockWebServer|WireMock|ProcessBuilder|Thread\.sleep",
                text,
            ):
                raise ValueError("Boundary or delayed test cannot be unit: " + relative)
    if actual != files.keys():
        raise ValueError(
            "Remove stale classification entries: "
            + ", ".join(sorted(files.keys() - actual))
        )
    return {
        tier: sum(t == tier for t in files.values())
        for tier in ("unit", "integration", "slow", "live")
    }


if __name__ == "__main__":
    print(json.dumps(validate(), sort_keys=True))
