"""Validate the reviewed inventory used by native Gradle unit/boundary tasks."""

from pathlib import Path
import json
import re

ROOT = Path(__file__).resolve().parents[1]
MODULES = ("backend", "telegram-bot", "cli", "shared")
TEST_DECLARATION = re.compile(r"@(Test\b|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)|extends\s+Specification")
BOUNDARY = re.compile(
    r"@(SpringBootTest|WebMvcTest|DataJpaTest|ContextConfiguration|SpringJUnitConfig|SpringJUnitWebConfig)|SpringExtension|"
    r"new\s+(?:org\.springframework\.[\w.]+\.)?"
    r"(?:Static|Generic|GenericXml|AnnotationConfig|AnnotationConfigWeb|ClassPathXml|FileSystemXml|XmlWeb|StaticWeb|GenericWeb)ApplicationContext\s*\(|"
    r'@Tag\s*\(\s*"(?:integration|slow|live|e2e)"\s*\)|'
    r"Testcontainers|PostgresTestContainer|DisposablePostgres|ServerSocket|HttpServer|"
    r"MockWebServer|WireMock|ProcessBuilder|Thread\.sleep"
)


def without_comments(text):
    # Preserve strings: Class.forName and resource-based fixture references are
    # real consumers. Documentation references alone are not classpath edges.
    tokens = r'"(?:\\.|[^"\\])*"|\x27(?:\\.|[^\x27\\])*\x27|//[^\n]*|/\*[\s\S]*?\*/'
    return re.sub(tokens, lambda match: '' if match[0].startswith(('//', '/*')) else match[0], text)


def compilation_layout(root=ROOT, inventory=None, sources=None):
    """Reviewed source partition, including helper closures, without a compiler."""
    inventory = inventory or json.loads((root / 'gradle/test-lanes.json').read_text(encoding='utf-8'))
    if sources is None:
        sources = {path.relative_to(root).as_posix(): path.read_text(encoding='utf-8')
                   for module in MODULES for path in (root / module / 'src/test').rglob('*')
                   if path.suffix in ('.java', '.groovy')}
    tests, support = inventory['files'], inventory.get('support', {})
    if not isinstance(support, dict) or set(support) & set(tests):
        raise ValueError('Test and support inventories must be disjoint')
    if set(tests) | set(support) != set(sources):
        raise ValueError('Classify every test support source; remove stale support entries: '
                         + ', '.join(sorted(set(sources) ^ (set(tests) | set(support)))))
    result = {module: {'unit': [], 'manual': [], 'shared': []} for module in MODULES}
    for name, body in sources.items():
        parts = name.split('/')
        if (len(parts) < 5 or parts[0] not in result or parts[1:3] != ['src', 'test']
                or parts[3] not in ('java', 'groovy') or '..' in parts or '\\' in name):
            raise ValueError('Unsupported test source path: ' + name)
        if name in tests and tests[name] not in ('unit', 'integration', 'slow', 'live'):
            raise ValueError('Unknown test tier: ' + name)
        role = ('unit' if tests[name] == 'unit' else 'manual') if name in tests else support[name]
        if role not in ('unit', 'manual', 'shared'):
            raise ValueError('Unsupported test support tier: ' + name)
        if name in support and role in ('unit', 'shared') and BOUNDARY.search(body):
            raise ValueError('Boundary helper cannot enter unit/shared compilation: ' + name)
        result[parts[0]][role].append(name)
    for module, lanes in result.items():
        manual = {Path(name).stem for name in lanes['manual']}
        pattern = re.compile(r'\b(?:' + '|'.join(re.escape(name) for name in sorted(manual)) + r')\b') if manual else None
        for name in lanes['unit'] + lanes['shared']:
            if pattern and pattern.search(without_comments(sources[name])):
                raise ValueError('Unit/shared source depends on a manual class; extract a shared fixture: ' + name)
        for names in lanes.values():
            names.sort()
    return result


def validate(root=ROOT):
    inventory = json.loads(
        (root / "gradle/test-lanes.json").read_text(encoding="utf-8")
    )
    files = inventory["files"]
    actual = set()
    sources = {}
    for module in MODULES:
        for path in (root / module / "src/test").rglob("*"):
            if path.suffix not in (".java", ".groovy"):
                continue
            text = path.read_text(encoding="utf-8")
            sources[path.relative_to(root).as_posix()] = text
            if not TEST_DECLARATION.search(text):
                continue
            relative = path.relative_to(root).as_posix()
            actual.add(relative)
            if relative not in files:
                raise ValueError("Classify new test before build: " + relative)
            if files[relative] not in ("unit", "integration", "slow", "live"):
                raise ValueError("Unknown test tier: " + relative)
            if files[relative] == "unit" and BOUNDARY.search(text):
                raise ValueError("Boundary or delayed test cannot be unit: " + relative)
    if actual != files.keys():
        raise ValueError(
            "Remove stale classification entries: "
            + ", ".join(sorted(files.keys() - actual))
        )
    compilation_layout(root, inventory, sources)
    return {
        tier: sum(t == tier for t in files.values())
        for tier in ("unit", "integration", "slow", "live")
    }


if __name__ == "__main__":
    print(json.dumps(validate(), sort_keys=True))
