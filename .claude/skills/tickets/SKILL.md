---
name: tickets
description: Work with the file tickets of the XMB launcher (tickets/LNC-*.md) through the node tracker `node tools/tickets.mjs` on any OS — create a ticket for the author's decision, a task, a bug or deferred work; move it through its statuses with commits; pick the right type (decision vs feature/tech) and area; fix tickets that fail validate; rebuild the board and commit it. Use whenever the project's CLAUDE.md asks for a ticket (a decision made in the dialog, work started or done, a bug out of scope, end of session), when the user says тикет/задача/заведи тикет/закрой тикет/доска/validate, or before committing changes under tickets/.
---

# Tickets (XMB launcher)

The tracker is `node tools/tickets.mjs` (no packages, any OS; `tools/tickets.ps1` and `tools/tickets.sh` only call
it). Run it from the repo root. Fields, statuses and areas: `tickets/README.md`.

## Who reads what you write

- **The author who decided** — a `decision` says what was decided, why, what was rejected and what code and agents
  must now keep to.
- **The author who checks** — the note on `fixed` says what to try on the phone (or a handheld): which look, which
  orientation, which buttons — so they can mark it on the board's «Ревью» without asking you.
- **The next agent** — «История», `links` and `commits` let a session with no memory pick the work up: write what
  happened and what is left, not «done».
- **You, mid-task** — a bug or deferred work out of scope becomes `new -Type bug|tech`, not a TODO in the chat.
- **Whoever reads the code later** — put `LNC-…` in the commit and in a comment on a rule that looks odd.

## The rule that matters

**Never write a ticket file by hand.** `new` takes the number (the author's letter from `tickets/authors.json` by git
e-mail + the next shared number: `LNC-A0042`), the slug, the template and the first history line; `set` changes the
frontmatter, appends to «История», bumps `updated` and refuses a ticket that would not validate. Only the body text
(sections above «История») is edited by hand.

## Pick the type

| What it is | type | statuses |
|---|---|---|
| The author decided something (approach, rule, default) | `decision` | `accepted` → `superseded` only |
| Work to build | `feature` | `open` → `in-progress` → `fixed` → (`verified` by the author) |
| Something broken | `bug` | same as feature |
| Internal work, refactor, tooling | `tech` | same; `verify-in` may be `none` |
| An open question for the author | `question` | same |

The work a decision calls for is its own `feature`/`tech` with `-Links LNC-<decision>`.

## Pick the area

`ui` (XmbView) · `look` (looks, background, shaders, fonts, wallpaper) · `icons` · `search` · `apps` (app list,
sections, Frequent, dock, shortcuts) · `input` (gestures, haptics, gamepad, Essential key, rotation) · `media`
(photo/music/video) · `system` (notifications, calendar, status bar, default launcher) · `store` (flavors, signing,
Play, `docs/play/`) · `docs` · `infra` (build, SDK, emulator, tracker).

## Recipes

```bash
node tools/tickets.mjs new -Type decision -Area store -Source user -Title "..." -Note "решение автора в диалоге"
node tools/tickets.mjs new -Type feature -Area media -Title "..." -Links LNC-A0003
node tools/tickets.mjs set -Id LNC-A0004 -Status in-progress
node tools/tickets.mjs set -Id LNC-A0004 -Status fixed -Commits abc1234 -Note "что сделано; что попробовать на телефоне"
node tools/tickets.mjs board && node tools/tickets.mjs validate   # must print OK
```

- `verify-in`: `review` (the author on their phone, default) · `device` (a handheld/tablet the author borrows) ·
  `emulator` (you, on the own AVD `xmb`, port 5556) · `build` · `none`.
- Your own checks come before `fixed`: build, the phone over ADB (never `am force-stop dev.anded.xmb` — it resets
  the live wallpaper and turns the accessibility service off), the emulator for other screens.

## Never

- `verified` on your own — only on the author's word (or `apply` of their board feedback).
- Reopen a terminal ticket without the author; edit `tickets/BOARD.md`; commit `tickets/board.html`.

## Commit

Tickets go in the same commit as the code, or right after it with `set -Commits <hash>`, `board`, commit `tickets/`.
The repo is GitHub `Anded116/android-xmb` (to become private), branch `main`, author e-mail the GitHub noreply one
(already in `.git/config`). Nothing personal in tickets: no phone serial, local paths, e-mails, lists of the
author's apps.
