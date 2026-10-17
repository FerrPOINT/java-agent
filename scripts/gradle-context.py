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


def plain(path, root, checked=None):
    """Never follow a source/cache alias while inspecting prepared inputs."""
    path, root = Path(path).absolute(), Path(root).absolute()
    if '..' in path.parts or not path.is_relative_to(root):
        raise ValueError('Prepared input leaves its declared root')
    for item in (path, *path.parents):
        if checked is not None and item in checked:
            continue
        try:
            info = item.lstat()
        except OSError as error:
            raise ValueError('Prepared input is unavailable') from error
        if item.is_symlink() or getattr(info, 'st_file_attributes', 0) & 0x400:
            raise ValueError('Prepared inputs cannot traverse links')
        if checked is not None:
            checked.add(item)
    return path


def distribution_snapshot(directory, home):
    """Recognize only the committed wrapper layouts; no wrapper/bootstrap process."""
    properties = plain(directory / 'gradle/wrapper/gradle-wrapper.properties', directory)
    with properties.open('rb') as stream:
        payload = stream.read(65537)
    if len(payload) > 65536:
        raise ValueError('Wrapper properties exceed their budget')
    values = {}
    for line in payload.decode('utf-8').splitlines():
        if not line.strip() or line.lstrip().startswith(('#', '!')):
            continue
        if '=' not in line:
            raise ValueError('Unsupported prepared wrapper properties')
        key, value = (part.strip() for part in line.split('=', 1))
        if not re.fullmatch(r'[A-Za-z][A-Za-z0-9]*', key) or '\\' in value.replace('\\:', ':'):
            raise ValueError('Prepared wrapper properties require the committed literal format')
        if key in values:
            raise ValueError('Duplicate wrapper property')
        values[key] = value.replace('\\:', ':')
    for key, expected in (('distributionBase', 'GRADLE_USER_HOME'),
                          ('zipStoreBase', 'GRADLE_USER_HOME'),
                          ('distributionPath', 'wrapper/dists'), ('zipStorePath', 'wrapper/dists')):
        if values.get(key) != expected:
            raise ValueError('Prepared wrapper layout is not qualified')
    url = values.get('distributionUrl', '')
    version = re.fullmatch(r'https://services\.gradle\.org/distributions/gradle-(9\.6\.1|9\.8\.0)-bin\.zip', url)
    if not version:
        raise ValueError('Prepared distribution URL is not qualified')
    version = version[1]
    # Match Gradle 9.6.1/9.8.0 PathAssembler: URL MD5 as an unsigned base36
    # directory name. This locates the wrapper cache; integrity uses SHA-256.
    number = int.from_bytes(hashlib.md5(url.encode(), usedforsecurity=False).digest(), 'big')
    key = ''
    while number:
        number, remainder = divmod(number, 36)
        key = '0123456789abcdefghijklmnopqrstuvwxyz'[remainder] + key
    parent = home / 'wrapper/dists' / ('gradle-' + version + '-bin') / (key or '0')
    parent = plain(parent, home)
    marker = plain(parent / ('gradle-' + version + '-bin.zip.ok'), home)
    distribution = plain(parent / ('gradle-' + version), home)
    for item in parent.iterdir():
        plain(item, home)
    if not marker.is_file() or [item for item in parent.iterdir() if item.is_dir()] != [distribution]:
        raise ValueError('Wrapper distribution is not ready')
    library = plain(distribution / 'lib', home)
    launchers = [item for item in library.iterdir()
                 if re.fullmatch(r'gradle-launcher-.*\.jar', item.name)]
    if (not plain(distribution / 'bin/gradle', home).is_file()
            or launchers != [distribution / 'lib' / ('gradle-launcher-' + version + '.jar')]
            or not plain(launchers[0], home).is_file()):
        raise ValueError('Wrapper distribution launcher is missing')
    state = inventory(distribution)
    if not state['complete']:
        raise ValueError('Wrapper distribution inventory is incomplete')
    return version, state


def dependency_snapshot(repository, home, modules, version, backend, role):
    """Validate selected external role bytes, sharing reads across module indexes."""
    locations = {'backend': ('backend', ':' if backend else ':backend'),
                 'shared': ('shared', ':shared'), 'cli': ('cli', ':cli'), 'bot': ('telegram-bot', ':telegram-bot')}
    if (role not in ('production', 'unit') or not modules or len(modules) != len(set(modules))
            or any(name not in locations or (backend and name not in ('backend', 'shared')) for name in modules)):
        raise ValueError('Prepared modules/role do not match the selected wrapper')
    records, checked, consumed = {}, set(), 0

    def unique(pairs):
        value = {}
        for name, item in pairs:
            if name in value:
                raise ValueError('Duplicate dependency index key')
            value[name] = item
        return value

    for name in sorted(modules):
        relative, identity = locations[name]
        path = plain(repository / relative / 'build/quality' / ('gradle-' + version + '-dependency-inputs.json'), repository)
        with path.open('rb') as stream:
            payload = stream.read(4 * 1024**2 + 1)
        if len(payload) > 4 * 1024**2:
            raise ValueError('Dependency index exceeds its byte budget')
        try:
            value = json.loads(payload, object_pairs_hook=unique)
        except (UnicodeError, RecursionError, json.JSONDecodeError) as error:
            raise ValueError('Invalid dependency index JSON') from error
        if (not isinstance(value, dict) or set(value) != {'schema', 'module', 'gradle', 'roles', 'files'}
                or value.get('schema') != 'java-gradle-dependency-inputs/v1'
                or value.get('module') != identity or value.get('gradle') != version):
            raise ValueError('Dependency index does not match module/wrapper')
        roles, files = value.get('roles'), value.get('files')
        if not isinstance(roles, dict) or set(roles) != {'production', 'unit'} or not isinstance(files, dict) or len(files) > 4096:
            raise ValueError('Invalid dependency index roles/inventory')
        for members in roles.values():
            if (not isinstance(members, list) or any(not isinstance(member, str) for member in members)
                    or members != sorted(set(members))):
                raise ValueError('Invalid dependency role members')
        if not set(roles['production']).issubset(roles['unit']) or set(files) != set(roles['unit']):
            raise ValueError('Dependency index role closure is incomplete')
        total = 0
        for filename, row in files.items():
            candidate = Path(filename)
            if not candidate.is_absolute() or '..' in candidate.parts or not candidate.is_relative_to(home):
                raise ValueError('Dependency index leaves the Gradle cache root')
            if (not isinstance(row, dict) or set(row) != {'sha256', 'bytes'}
                    or not isinstance(row['sha256'], str) or not re.fullmatch(r'[a-f0-9]{64}', row['sha256'])
                    or type(row['bytes']) is not int or row['bytes'] < 0):
                raise ValueError('Invalid dependency file record')
            total += row['bytes']
        if total > 2 * 1024**3:
            raise ValueError('Dependency index exceeds its file budget')
        for filename in roles[role]:
            file = plain(Path(filename), home, checked)
            row = files[filename]
            if filename in records:
                if records[filename] != row:
                    raise ValueError('Conflicting dependency integrity records')
                continue
            if len(records) >= 4096 or consumed + row['bytes'] > 2 * 1024**3:
                raise ValueError('Selected dependency closure exceeds its budget')
            if not file.is_file() or file.stat().st_size != row['bytes']:
                raise ValueError('Dependency integrity differs from preparation')
            hashed, size = hashlib.sha256(), 0
            with file.open('rb') as stream:
                for block in iter(lambda: stream.read(1024 * 1024), b''):
                    size += len(block)
                    if size > row['bytes']:
                        raise ValueError('Dependency grew during integrity validation')
                    hashed.update(block)
            if size != row['bytes'] or hashed.hexdigest() != row['sha256']:
                raise ValueError('Dependency integrity differs from preparation')
            records[filename] = row
            consumed += size
    return {'sha256': digest({'modules': sorted(modules), 'role': role, 'files': records}),
            'files': len(records), 'bytes': sum(row['bytes'] for row in records.values()), 'role': role}


def snapshot(repository, backend, environment, *, prepared_modules=None, role='unit'):
    if prepared_modules is not None:
        plain(repository, repository)
    repository = repository.resolve()
    directory = repository / 'backend' if backend else repository
    selected = java_details(environment)
    home = Path(environment.get('GRADLE_USER_HOME') or Path(environment.get('HOME') or Path.home()) / '.gradle')
    if prepared_modules is not None:
        plain(home, home)
    home = home.resolve()
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
    if prepared_modules is not None:
        version, distribution = distribution_snapshot(directory, home)
        inputs['prepared'] = {'gradle_version': version, 'distribution': distribution,
                              'dependencies': dependency_snapshot(repository, home, prepared_modules, version, backend, role)}
    return {'schema': 'java-gradle-inputs/v1', 'java_version': selected['java_version'],
            'inputs_sha256': digest(inputs), 'inputs': inputs,
            'reuse_qualified': False,
            'unqualified_reason': ('PREPARED_CONTEXT_PROVENANCE_AND_NATIVE_JAR_PROOF_PENDING' if prepared_modules is not None
                                   else 'GRADLE_DISTRIBUTION_AND_RESOLVED_INPUT_CLOSURE_PENDING')}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--backend', action='store_true')
    parser.add_argument('--prepared-module', choices=('backend', 'shared', 'cli', 'bot'), action='append')
    parser.add_argument('--role', choices=('production', 'unit'), default='unit')
    args = parser.parse_args()
    try:
        result = snapshot(Path(__file__).resolve().parents[1], args.backend, os.environ,
                          prepared_modules=args.prepared_module, role=args.role)
    except (OSError, ValueError, subprocess.SubprocessError, KeyError) as error:
        print('Gradle context unavailable: ' + type(error).__name__, file=sys.stderr)
        return 2
    print(json.dumps(result, sort_keys=True, separators=(',', ':')))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
