---
id: LNC-A0017
title: «Назад» на Android 13–15 закрывал лаунчер
type: bug
status: fixed
priority: P1
area: system
source: agent
verify-in: device
created: 2026-11-04
updated: 2026-11-04
commits: 4670594
---

## Описание

<наблюдаемое поведение и ожидаемое>

## Репро

<шаги; устройство, сборка (full/store), оформление, ориентация>

## Критерий закрытия

<что должно быть правдой, чтобы тикет закрыть>

## История

- 2026-11-04 (agent): Найдено на эмуляторе Android 14: без android:enableOnBackInvokedCallback система не звала OnBackInvokedCallback и закрывала задачу, лаунчер перезапускался с потерей состояния. На телефоне с Android 16 не видно — там флаг включён целевым API 36. Исправлено в 4670594, проверено на эмуляторе.
- 2026-11-04 (agent): status open -> in-progress
- 2026-11-04 (agent): status in-progress -> fixed; commits 4670594 - Проверить на любом устройстве с Android 13–15: «назад» в лаунчере ничего не закрывает, в папке — уровень вверх.
