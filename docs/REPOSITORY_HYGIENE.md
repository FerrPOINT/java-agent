# Git-гигиена репозиториев

Канонические исходники проверки — `scripts/check_repository_hygiene.py`,
`scripts/check_repository_hygiene_push.sh` и соответствующий unittest в Base.
Продукты хранят идентичные автономные копии: Python 3.10+ и Git достаточны,
SDK/UI/skills pins и работающий runtime не нужны. При изменении канонического
проверяющего скрипта обновлять копии одним согласованным набором PR и сверять SHA-256.

```sh
python3 -B scripts/check_repository_hygiene.py --index
python3 -B scripts/check_repository_hygiene.py --ref HEAD
python3 -B scripts/tests/test_repository_hygiene.py
```

Без аргументов проверяется index. `--ref` принимает commit или commit-ish,
включая annotated tag на commit. Код 0 означает отсутствие нарушений, 1 —
запрещённые отслеживаемые пути, 2 — невозможность полной проверки. Результат
содержит JSON с путями и причинами; содержимое файлов и Git stderr не выводятся.
Неподготовленные файлы и неотслеживаемые кэши не проверяются. Конфликтный index
отклоняется. Запуск из вложенного каталога проверяет весь репозиторий.

Проверка запрещает каталоги Python/Node/venv-кэшей, `.local`, worktree,
Rust `target`, `target-cache`, `target-cache<digits>`, `target-stage`,
`target-stable`, bytecode `.pyc`/`.pyo` и TypeScript `.tsbuildinfo`.
Обычные `.jar`, `.log`, `.json`, `dist`, generated clients и архивные `.source`
сами по себе нарушением не являются. Recovery-архивы и связанные QA-доказательства
сохраняются. Проверка не является сканером секретов или очисткой истории.

## Локальные gates и hooks

В продуктах с Lefthook используются существующие pre-commit/pre-push commands.
В остальных — `.githooks/pre-commit` и `.githooks/pre-push`:

```sh
git config --local core.hooksPath .githooks
```

Перед настройкой проверить существующий `core.hooksPath`; чужие hooks не
перезаписывать. В shared worktree учитывать, что local Git config общий.
Для Lefthook использовать штатный `lefthook install`, сохраняя остальные checks.
Pre-commit проверяет index, pre-push получает от Git конкретные отправляемые SHA;
удаление ref пропускается. `PYTHON` в shell hook позволяет указать путь Python
без дополнительных аргументов. Установка hooks — настройка checkout, не серверная
защита: прямые команды и клиент без hooks могут её обойти. Поэтому перед
публикацией дополнительно запускать `--ref` на точном кандидате.
GitHub Actions для этой проверки не включаются.

## Очистка после аудита 2026-10-28

В Tracker удаляются три отслеживаемых `.pyc`, в Wiki — два; источники сохраняются.
Опубликованная история не переписывается. Старые ветки удаляются только при
подтверждённой достижимости из default branch, отсутствии открытого PR, protection
и использования, с повторной проверкой ожидаемого SHA. Уникальные коммиты сохраняются.
Локальные повреждённые checkout не удаляются по имени или отсутствию HEAD:
при неизвестном содержимом либо владельце сохраняются и заносятся в реестр.
Runtime, backups, credentials, volumes и SDK/UI pins эта работа не изменяет.
