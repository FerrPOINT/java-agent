# Восстановленные исходники workspace

Этот PR сохраняет незалитые варианты исходников отдельными инертными payloads.
Он не подключает их к runtime, импортам, миграциям, CI или production bundle.
Рабочие деревья sdlc/sdlc2/sdlc3 сохранены без изменений.

| Группа | Версий файлов |
|---|---:|
| source-recovery | 31 |
| workspace-operations | 1 |

`index.json` сопоставляет каждый payload с исходным путём, Git HEAD, worktree и SHA-256.
Варианты одного пути сохраняются отдельно: это не список изменений для массового применения.
Суффикс `.source` исключает случайный импорт или обнаружение тестов из старых веток.
Текст нормализован только с CRLF на LF. Проверка целостности: `python handoff/workspace-recovery-20261020/verify.py`.

Перед переносом в рабочие модули необходимо сравнить варианты с актуальной веткой,
сохранить её namespace/auth/admission guards, разнести изменения по функциональным PR,
перегенерировать OpenAPI и квалифицировать тесты на закреплённых toolchains.
Выпущенные migrations и fingerprints не переписывать. Незавершённые merge markers
отмечены в индексе; такие payloads не являются компилируемым кандидатом.

Generated OpenAPI/client snapshots, screenshots, private QA evidence, caches и runtime
не копируются. Отсутствие live qualification указано явно: существующие результаты
на старых source heads не считаются проверкой текущей интеграции.

Дополнительно сохранены текстовые diffs локальных коммитов, отсутствовавших
в origin при повторной проверке. Они находятся в группе `committed-work`; SHA
и список включённых путей записаны в индексе. Generated schemas, binary assets
и private evidence из этих коммитов исключены. Это исторические diffs к своим
родителям: наличие diff не доказывает отсутствие эквивалентного решения в main.

Scoped `.gitattributes` сохраняет точные LF-байты `.source` при checkout,
включая Windows с `core.autocrlf=true`; SHA-256 verifier переносим между ОС.
