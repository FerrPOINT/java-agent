"""Translate the canonical Base plan into two existing Gradle invocations."""
import json
import os
from pathlib import Path
import re

from scripts.vendor import base_quality_selection as selector

ROOT = Path(__file__).resolve().parents[1]
CLIENT_TASKS = {f':{module}:{task}' for module in ('cli', 'telegram-bot', 'shared')
                for task in ('test', 'bootJar')}
BACKEND_TASKS = {':test', ':bootJar'}


def tasks_for_plan(plan):
    result = {'backend': [], 'clients': []}
    for gate in plan['gates']:
        if 'scripts/quality-gradle.sh' not in gate['argv']:
            continue
        if gate.get('test_tier') not in ('unit', 'artifact'):
            raise ValueError('Automatic CI accepts only reviewed units/artifacts')
        name = 'backend' if '--backend' in gate['argv'] else 'clients'
        allowed = BACKEND_TASKS if name == 'backend' else CLIENT_TASKS
        tasks = [arg for arg in gate['argv'] if arg.startswith(':')]
        if not tasks or set(tasks) - allowed:
            raise ValueError('Unsupported Gradle tasks in the selected Base plan')
        for task in tasks:
            if task not in result[name]:
                result[name].append(task)
    return result


def ci_plan(root, event, base, head):
    if event not in ('push', 'pull_request') or not re.fullmatch(r'[a-f0-9]{40}', head or ''):
        raise ValueError('Automatic CI requires an exact event/head')
    if selector.git(root, 'rev-parse', 'HEAD') != head:
        raise ValueError('Selected checkout differs from the classified head')
    if not re.fullmatch(r'[a-f0-9]{40}', base or '') or (base == '0' * 40 and event != 'push'):
        raise ValueError('Automatic CI requires an exact comparison base')
    if base == '0' * 40:
        selected = selector.plan(root, profile='build', components=list(selector.load(root)['components']))
    else:
        # This preserves staged/deleted/untracked semantics locally; CI's exact
        # clean checkout compares the fetched base using the same Base planner.
        selected = selector.plan(root, profile='build', base=base)
    return tasks_for_plan(selected)


def main():
    value = ci_plan(ROOT, os.environ.get('EVENT_NAME'), os.environ.get('BASE_SHA'), os.environ.get('HEAD_SHA'))
    output = ''.join(name + '_tasks=' + json.dumps(tasks, separators=(',', ':')) + '\n'
                     + 'has_' + name + '=' + str(bool(tasks)).lower() + '\n' for name, tasks in value.items())
    if os.environ.get('GITHUB_OUTPUT'):
        with Path(os.environ['GITHUB_OUTPUT']).open('a', encoding='utf-8') as stream:
            stream.write(output)
    else:
        print(output, end='')


if __name__ == '__main__':
    main()
