"""Read-only Gradle/JDK inputs. Never invokes Gradle, installs or emits secrets."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

ENVIRONMENT = ('JAVA_HOME', 'GRADLE_USER_HOME', 'GRADLE_HOME', 'JAVA_OPTS',
               'GRADLE_OPTS', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS',
               'CLASSPATH', 'JAVA_MODULE_PATH', 'LD_PRELOAD', 'LD_LIBRARY_PATH',
               'GRADLE_RO_DEP_CACHE', 'GRADLE_LIBS_REPO_OVERRIDE')


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def content(path):
    result = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            result.update(block)
    return result.hexdigest()


def inventory(root):
    """Bound one read-only tree; no mtime keys or traversal through external links."""
    rows, visited, count, size, complete, entries = {}, {}, 0, 0, True, 0
    if root.is_symlink() or (root.exists() and getattr(root.lstat(), 'st_file_attributes', 0) & 0x400):
        return {'sha256': digest({'link': os.readlink(root)}), 'files': 0, 'bytes': 0, 'complete': False}

    def paths():
        pending = [root] if root.is_dir() else []
        while pending:
            with os.scandir(pending.pop()) as items:
                for item in items:
                    path = Path(item.path)
                    linked = item.is_symlink() or getattr(path.lstat(), 'st_file_attributes', 0) & 0x400
                    if item.is_dir(follow_symlinks=False) and not linked:
                        pending.append(path)
                    yield path

    for path in paths():
        entries += 1
        if entries > 20000:
            complete = False
            break
        relative = path.relative_to(root).as_posix()
        target = path.resolve()
        if path.is_symlink() or getattr(path.lstat(), 'st_file_attributes', 0) & 0x400:
            if not target.is_relative_to(root.resolve()) or path.is_dir():
                rows[relative] = {'link': digest(os.readlink(path))}
                complete = False
                continue
        if path.is_dir():
            continue
        if not path.is_file():
            complete = False
            continue
        count += 1
        size += path.stat().st_size
        if size > 2 * 1024**3:
            complete = False
            break
        if target not in visited:
            visited[target] = content(path)
        rows[relative] = {'sha256': visited[target], 'executable': bool(path.stat().st_mode & 0o111)}
    return {'sha256': digest(rows), 'files': count, 'bytes': size, 'complete': complete}


def java_details(environment):
    selected = (Path(environment['JAVA_HOME']) / 'bin' / ('java.exe' if os.name == 'nt' else 'java')) \
        if environment.get('JAVA_HOME') else Path(shutil.which('java', path=environment.get('PATH')) or 'java')
    executable = selected.resolve(strict=True)
    clean = {key: value for key, value in environment.items()
             if key not in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS',
                            'LD_PRELOAD', 'LD_LIBRARY_PATH')}
    result = subprocess.run([str(executable), '-XshowSettings:properties', '-version'],
                            env=clean, capture_output=True, text=True, timeout=15)
    if result.returncode:
        raise ValueError('Selected Java probe failed')
    properties = dict(re.findall(r'^\s*(java\.(?:home|version|vendor))\s*=\s*(.*?)\s*$',
                                 result.stdout + result.stderr, re.M))
    version = properties.get('java.version', '')
    if not re.fullmatch(r'[0-9]+(?:\.[0-9]+)*(?:[-+][A-Za-z0-9.]+)*', version):
        raise ValueError('Selected Java returned no valid version')
    home = Path(properties['java.home']).resolve(strict=True)
    if not executable.is_relative_to(home):
        raise ValueError('Selected Java and reported Java home differ')
    return {'java_version': version, 'home': home, 'executable': executable,
            'vendor_sha256': digest(properties.get('java.vendor'))}


def snapshot(repository, backend, environment):
    repository = repository.resolve()
    directory = repository / 'backend' if backend else repository
    selected = java_details(environment)
    home = Path(environment.get('GRADLE_USER_HOME') or Path(environment.get('HOME') or Path.home()) / '.gradle').resolve()
    configuration = {}
    for name in ('gradle.properties', 'init.gradle', 'init.gradle.kts'):
        path = home / name
        if path.is_symlink() or (path.exists() and not path.resolve().is_relative_to(home)):
            configuration[name] = {'link': digest(os.readlink(path))}
        else:
            configuration[name] = content(path) if path.is_file() else None
    configuration['init.d'] = inventory(home / 'init.d')
    wrapper = directory / 'gradle/wrapper'
    wrapper_files = {name: content(wrapper / name) if (wrapper / name).is_file() else None
                     for name in ('gradle-wrapper.properties', 'gradle-wrapper.jar')}
    criteria = directory / 'gradle/gradle-daemon-jvm.properties'
    names = set(ENVIRONMENT) | {name for name in environment if name.startswith('ORG_GRADLE_PROJECT_')}
    inputs = {'jdk': inventory(selected['home']), 'jdk_home_sha256': digest(str(selected['home'])),
              'java_executable_sha256': content(selected['executable']),
              'java_vendor_sha256': selected['vendor_sha256'],
              'gradle_home_sha256': digest(str(home)), 'gradle_configuration': configuration,
              'wrapper': wrapper_files, 'backend': backend,
              'daemon_jvm_criteria': content(criteria) if criteria.is_file() else None,
              'environment_sha256': {name: digest(environment.get(name) or None) for name in sorted(names)}}
    return {'schema': 'java-gradle-inputs/v1', 'java_version': selected['java_version'],
            'inputs_sha256': digest(inputs), 'inputs': inputs,
            'reuse_qualified': False,
            'unqualified_reason': 'GRADLE_DISTRIBUTION_AND_RESOLVED_INPUT_CLOSURE_PENDING'}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--backend', action='store_true')
    args = parser.parse_args()
    try:
        result = snapshot(Path(__file__).resolve().parents[1], args.backend, os.environ)
    except (OSError, ValueError, subprocess.SubprocessError, KeyError) as error:
        print('Gradle context unavailable: ' + type(error).__name__, file=sys.stderr)
        return 2
    print(json.dumps(result, sort_keys=True, separators=(',', ':')))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
