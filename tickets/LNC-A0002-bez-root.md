---
id: LNC-A0002
title: Без root
type: decision
status: accepted
priority: P2
area: system
source: user
verify-in: none
created: 2026-11-04
updated: 2026-11-04
---

## Решение

Лаунчер работает без root: никаких `su`, `policy_control`, оверлеев `cmd overlay` и правок системных разделов.

## Контекст

Хотелось скрывать строку состояния в альбоме и в чужих приложениях. Без root это невозможно, а root ломает банки, Play Integrity и Mir Pay.

## Последствия

Строка состояния управляется только в своём окне (`WindowInsetsController`); в чужих приложениях — как решит система.

## История

- 2026-11-04 (agent): решение автора в диалоге
