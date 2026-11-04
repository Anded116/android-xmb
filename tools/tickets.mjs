#!/usr/bin/env node
// tickets.mjs - file-based ticket tracker for the XMB launcher, the same on Windows, macOS and Linux.
//
// One markdown file per ticket in tickets/, flat "key: value" frontmatter. Node only: no packages, no network.
// tools/tickets.ps1 and tools/tickets.sh only call this file. Ported from ainimator (G:\work\aispine).
//
//   node tools/tickets.mjs new -Type bug -Title "..." -Priority P1 -Area nav
//   node tools/tickets.mjs set -Id LNC-A0042 -Status fixed -Commits abc1234 -Note "..."
//   node tools/tickets.mjs list [-Status open] [-Type bug] [-Area nav] [-Priority P0]
//   node tools/tickets.mjs checklist | board | html | validate | renumber [-Path ...] | apply [-Path ...]
//
// Options are spelled as before (-Title, case does not matter) or as --title. Docs: tickets/README.md.

import { spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, readdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { basename, dirname, isAbsolute, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

// ---------------------------------------------------------------- constants --

const TYPES = ['bug', 'feature', 'tech', 'decision', 'question'];
const STATUSES = ['open', 'in-progress', 'fixed', 'verified', 'closed', 'wontfix', 'cannot-reproduce', 'accepted', 'superseded'];
const PRIORITIES = ['P0', 'P1', 'P2', 'P3'];
const AREAS = ['ui', 'look', 'icons', 'search', 'apps', 'input', 'media', 'system', 'store', 'docs', 'infra'];
const VERIFY = ['review', 'device', 'emulator', 'build', 'none'];
// Statuses that end a ticket's life; changing them requires -Force.
const TERMINAL = ['verified', 'closed', 'wontfix', 'superseded'];
// Statuses that mean "still on the board".
const ACTIVE = ['open', 'in-progress', 'fixed'];
// decision tickets live in their own two-state world.
const DECISION = ['accepted', 'superseded'];
const FIELD_ORDER = ['id', 'title', 'type', 'status', 'priority', 'area', 'source', 'verify-in', 'created', 'updated', 'links', 'commits', 'labels'];
const REQUIRED = ['id', 'title', 'type', 'status', 'priority', 'area', 'source', 'created', 'updated'];
const SOURCE_RE = /^(agent|user|review-\d{4}-\d{2}-\d{2})$/;
const HISTORY_RE = /^##\s+История\s*$/m;

// Ticket ids: LNC-A0042, the author's letter and the number; the file name starts with the id.
const PREFIX = 'LNC';

const TOOLS = dirname(fileURLToPath(import.meta.url));
const ROOT = dirname(TOOLS);

// ------------------------------------------------------------------ options --

const OPTION_NAMES = ['id', 'type', 'title', 'priority', 'area', 'source', 'verifyin', 'slug', 'status', 'commits', 'links', 'labels', 'note', 'path'];

/** @param {string[]} argv @returns {{command: string, opt: Record<string, string>, force: boolean}} */
function parseArgs(argv) {
  /** @type {Record<string, string>} */
  const opt = {};
  let command = '', force = false;
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    const m = /^--?([A-Za-z][A-Za-z-]*)(?::(.*))?$/.exec(arg);
    if (!m) {
      if (command) die(`unexpected argument '${arg}'`);
      command = arg;
      continue;
    }
    const name = m[1].toLowerCase().replace(/-/g, '');
    if (name === 'force') { force = true; continue; }
    if (!OPTION_NAMES.includes(name)) die(`unknown option '${arg}'`);
    const value = m[2] ?? argv[++i];
    if (value === undefined) die(`option '${arg}' needs a value`);
    opt[name] = value;
  }
  return { command: command || 'help', opt, force };
}

const { command: COMMAND, opt: OPT, force: FORCE } = parseArgs(process.argv.slice(2));
/** @param {string} name @returns {string} the option's value, '' when absent or blank */
const arg = name => (OPT[name] ?? '').trim() === '' ? '' : OPT[name];

// ------------------------------------------------------------------ helpers --

/** @param {string} message @returns {never} */
function die(message) {
  process.stderr.write(`ERROR: ${message}\n`);
  process.exit(1);
}

const COLOURS = { red: 31, green: 32, yellow: 33, cyan: 36 };
/** @param {string} text @param {keyof typeof COLOURS} [colour] */
function say(text, colour) {
  console.log(colour && process.stdout.isTTY ? `\x1b[${COLOURS[colour]}m${text}\x1b[0m` : text);
}

function ticketsDir() {
  const dir = join(ROOT, 'tickets');
  if (!existsSync(dir)) mkdirSync(dir, { recursive: true });
  return dir;
}

/** @param {string} path */
const readText = path => readFileSync(path, 'utf8').replace(/^﻿/, '');
/** @param {string} path @param {string} text */
const writeText = (path, text) => writeFileSync(path, text, 'utf8');

/** @param {Date} d */
const ymd = d => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
const today = () => ymd(new Date());

/** @param {string[]} allowed */
const joinAllowed = allowed => allowed.join(' | ');

// Cyrillic -> latin, for file name slugs.
/** @type {Record<string, string>} */
const TRANSLIT = {
  а: 'a', б: 'b', в: 'v', г: 'g', д: 'd', е: 'e', ё: 'e', ж: 'zh', з: 'z', и: 'i', й: 'y', к: 'k', л: 'l', м: 'm', н: 'n',
  о: 'o', п: 'p', р: 'r', с: 's', т: 't', у: 'u', ф: 'f', х: 'h', ц: 'ts', ч: 'ch', ш: 'sh', щ: 'sch', ъ: '', ы: 'y', ь: '',
  э: 'e', ю: 'yu', я: 'ya',
};

/** @param {string} text */
function slugOf(text) {
  let slug = '';
  for (const ch of text.toLowerCase()) slug += ch in TRANSLIT ? TRANSLIT[ch] : /[a-z0-9]/.test(ch) ? ch : '-';
  slug = slug.replace(/-+/g, '-').replace(/^-+|-+$/g, '');
  if (slug.length > 48) {
    slug = slug.slice(0, 48).replace(/^-+|-+$/g, '');
    // do not cut a word in half if we can avoid it
    const lastDash = slug.lastIndexOf('-');
    if (lastDash > 24) slug = slug.slice(0, lastDash);
  }
  return slug || 'ticket';
}

// ------------------------------------------------------------------- parsing --

/** @typedef {{path: string, fileName: string, fields: Map<string, string>, body: string, parseErrors: string[]}} Ticket */

/** @param {string} path @returns {Ticket} */
function readTicket(path) {
  const lines = readText(path).split(/\r?\n/);
  /** @type {Map<string, string>} */
  const fields = new Map();
  /** @type {string[]} */
  const errors = [];
  let bodyStart = 0;
  if (lines.length < 2 || lines[0].trim() !== '---') {
    errors.push('no frontmatter (file must start with ---)');
  } else {
    let closed = false;
    for (let i = 1; i < lines.length; i++) {
      const line = lines[i];
      if (line.trim() === '---') { bodyStart = i + 1; closed = true; break; }
      if (line.trim() === '') continue;
      const m = /^([a-z][a-z-]*):\s*(.*)$/.exec(line);
      if (m) fields.set(m[1], m[2].trim());
      else errors.push(`unparsable frontmatter line ${i + 1}: ${line}`);
    }
    if (!closed) errors.push('frontmatter is not closed by ---');
  }
  const body = bodyStart > 0 && bodyStart < lines.length ? lines.slice(bodyStart).join('\n') : '';
  return { path, fileName: basename(path), fields, body, parseErrors: errors };
}

/** @param {Ticket} ticket */
function saveTicket(ticket) {
  let text = '---\n';
  // known fields first, in canonical order, then anything unexpected
  for (const key of FIELD_ORDER) if (ticket.fields.has(key)) text += `${key}: ${ticket.fields.get(key)}\n`;
  for (const [key, value] of ticket.fields) if (!FIELD_ORDER.includes(key)) text += `${key}: ${value}\n`;
  text += '---\n';
  if (!ticket.body.startsWith('\n')) text += '\n';
  text += ticket.body;
  if (!text.endsWith('\n')) text += '\n';
  writeText(ticket.path, text);
}

/** @returns {Ticket[]} */
function allTickets() {
  const dir = ticketsDir();
  return readdirSync(dir, { withFileTypes: true })
    .filter(e => e.isFile() && e.name.toUpperCase().startsWith(`${PREFIX}-`) && /\.md$/i.test(e.name))
    .map(e => e.name).sort()
    .map(name => readTicket(join(dir, name)));
}

/** @param {string} id */
function findTicket(id) {
  if (!id) die('missing -Id');
  const want = `${id.toUpperCase()}-`;
  const dir = ticketsDir();
  const matched = readdirSync(dir).filter(n => n.toUpperCase().startsWith(want) && n.toLowerCase().endsWith('.md'));
  if (matched.length === 0) die(`ticket ${id.toUpperCase()} not found in ${dir}`);
  if (matched.length > 1) die(`several files for ${id.toUpperCase()} : ${matched.join(', ')}`);
  return readTicket(join(dir, matched[0]));
}

function nextNumber() {
  let max = 0;
  for (const t of allTickets()) {
    const m = new RegExp(`^${PREFIX}-[A-Z]?(\\d+)-`).exec(t.fileName);
    if (m) max = Math.max(max, Number(m[1]));
  }
  return max + 1;
}

/** The number of an id, its author's letter aside: for ordering by age. @param {string | undefined} id */
function idNumber(id) {
  const m = new RegExp(`^${PREFIX}-[A-Z]?(\\d+)$`).exec(id ?? '');
  return m ? Number(m[1]) : 0;
}

/** @param {string[]} args @returns {{code: number, lines: string[]}} */
function git(args) {
  const r = spawnSync('git', ['-C', ROOT, ...args], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] });
  return { code: r.status ?? 1, lines: (r.stdout ?? '').split(/\r?\n/).filter(Boolean) };
}

// Two people who have not pulled each other's tickets yet both take the next number: the author's letter keeps
// their tickets apart (LNC-A0397, LNC-K0397). The letter is looked up by git's e-mail in tickets/authors.json.
function authorLetter() {
  const email = (git(['config', 'user.email']).lines[0] ?? '').trim();
  if (!email) die('git does not know your e-mail: git config --global user.email "you@example.com"');
  const file = join(ticketsDir(), 'authors.json');
  /** @type {Record<string, string>} */
  const authors = existsSync(file) ? JSON.parse(readText(file)) : {};
  const letter = Object.entries(authors).find(([k]) => k.toLowerCase() === email.toLowerCase())?.[1];
  if (!letter || !String(letter).trim()) die(`no letter for ${email} in tickets/authors.json: add "${email}": "<a letter no one has>" and commit it`);
  return String(letter).toUpperCase();
}

const newTicketId = () => `${PREFIX}-${authorLetter()}${String(nextNumber()).padStart(4, '0')}`;

/** @param {Ticket} ticket @param {string} line */
function addHistoryLine(ticket, line) {
  const stamp = `- ${today()} (agent): ${line}`;
  if (HISTORY_RE.test(ticket.body)) {
    // append at the very end of the file (История is always the last section)
    const body = ticket.body.trimEnd();
    ticket.body = body.endsWith('## История') ? `${body}\n\n${stamp}\n` : `${body}\n${stamp}\n`;
  } else {
    ticket.body = `${ticket.body.trimEnd()}\n\n## История\n\n${stamp}\n`;
  }
}

// ----------------------------------------------------------------- templates --

/** @param {string} type */
function templateBody(type) {
  if (type === 'decision') {
    return '\n## Решение\n\n<что решили, одним-двумя абзацами>\n\n## Контекст\n\n<почему встал вопрос; ссылки на README.md / тикеты>\n\n'
      + '## Альтернативы\n\n<что ещё рассматривали и почему отклонили; убрать секцию, если альтернатив не было>\n\n'
      + '## Последствия\n\n<что теперь обязаны соблюдать код и агенты>\n\n## История\n';
  }
  if (type === 'bug') {
    return '\n## Описание\n\n<наблюдаемое поведение и ожидаемое>\n\n## Репро\n\n<шаги; устройство, сборка (full/store), оформление, ориентация>\n\n'
      + '## Критерий закрытия\n\n<что должно быть правдой, чтобы тикет закрыть>\n\n## История\n';
  }
  return '\n## Описание\n\n<суть задачи>\n\n## Критерий закрытия\n\n<что должно быть правдой, чтобы тикет закрыть>\n\n## История\n';
}

// ---------------------------------------------------------------- validation --

/** @param {Ticket} ticket @returns {string[]} */
function checkTicket(ticket) {
  const problems = [...ticket.parseErrors];
  const f = ticket.fields;
  for (const key of REQUIRED) if (!(f.get(key) ?? '').trim()) problems.push(`missing required field '${key}'`);
  if (problems.length && !f.has('id')) return problems;
  const id = f.get('id');
  if (id !== undefined) {
    if (!new RegExp(`^${PREFIX}-[A-Z]?\\d{4}$`).test(id)) problems.push(`id '${id}' must look like ${PREFIX}-A0042`);
    else if (!ticket.fileName.startsWith(`${id}-`)) problems.push(`id '${id}' does not match file name '${ticket.fileName}'`);
  }
  /** @type {[string, string[]][]} */
  const enums = [['type', TYPES], ['status', STATUSES], ['priority', PRIORITIES], ['area', AREAS]];
  for (const [key, allowed] of enums) {
    if (f.has(key) && !allowed.includes(f.get(key) ?? '')) problems.push(`${key} '${f.get(key)}' is not one of: ${joinAllowed(allowed)}`);
  }
  const verify = f.get('verify-in');
  if (verify && !VERIFY.includes(verify)) problems.push(`verify-in '${verify}' is not one of: ${joinAllowed(VERIFY)}`);
  const type = f.get('type');
  if ((type === 'bug' || type === 'feature') && !(verify ?? '').trim()) problems.push(`verify-in is required for type '${type}'`);
  if (f.has('source') && !SOURCE_RE.test(f.get('source') ?? '')) problems.push(`source '${f.get('source')}' must be agent, user or review-YYYY-MM-DD`);
  for (const key of ['created', 'updated']) {
    if (f.has(key) && !/^\d{4}-\d{2}-\d{2}$/.test(f.get(key) ?? '')) problems.push(`${key} '${f.get(key)}' must be YYYY-MM-DD`);
  }
  const status = f.get('status');
  if (type !== undefined && status !== undefined) {
    if (type === 'decision' && !DECISION.includes(status)) problems.push(`decision tickets may only be ${joinAllowed(DECISION)}, got '${status}'`);
    if (type !== 'decision' && DECISION.includes(status)) problems.push(`status '${status}' is only for decision tickets`);
  }
  if (!HISTORY_RE.test(ticket.body)) problems.push("no '## История' section");
  return problems;
}

function cmdValidate() {
  const tickets = allTickets();
  let failed = 0;
  /** @type {Map<string, string>} */
  const seen = new Map();
  for (const t of tickets) {
    const problems = checkTicket(t);
    const id = t.fields.get('id');
    if (id !== undefined) {
      if (seen.has(id)) problems.push(`duplicate id, also used by ${seen.get(id)}`);
      else seen.set(id, t.fileName);
    }
    if (problems.length) {
      failed++;
      say(`FAIL ${t.fileName}`, 'red');
      for (const p of problems) say(`     - ${p}`);
    }
  }
  // links can only be checked once every id is known
  for (const t of tickets) {
    const broken = (t.fields.get('links') ?? '').split(',').map(s => s.trim()).filter(ref => ref && !seen.has(ref));
    if (broken.length) {
      failed++;
      say(`FAIL ${t.fileName}`, 'red');
      say(`     - links point at missing tickets: ${broken.join(', ')}`);
    }
  }
  if (failed) {
    say('');
    say(`${failed} failing ticket(s) out of ${tickets.length}`, 'red');
    process.exit(1);
  }
  say(`OK: ${tickets.length} ticket(s) valid`, 'green');
}

// Two branches that each took the next free number meet in a merge or a rebase with one id twice. The copy
// already on the server keeps it (others have seen it and named it in commits); the one not sent yet moves on.
/** @param {string} file */
const onServer = file => git(['cat-file', '-e', `@{u}:tickets/${basename(file)}`]).code === 0;

/** @param {Ticket} ticket @param {string} why */
function moveTicketNumber(ticket, why) {
  const old = ticket.fields.get('id') ?? '';
  // The author stays the author: their letter is kept (none on a ticket from before the letters).
  const letter = new RegExp(`^${PREFIX}-([A-Z])\\d+$`).exec(old)?.[1] ?? '';
  const id = `${PREFIX}-${letter}${String(nextNumber()).padStart(4, '0')}`;
  const leaf = ticket.fileName.replace(new RegExp(`^${PREFIX}-[A-Z]?\\d+`), id);
  const target = join(dirname(ticket.path), leaf);
  if (git(['ls-files', '--error-unmatch', ticket.path]).code === 0) git(['mv', ticket.path, target]);
  else renameSync(ticket.path, target);
  ticket.path = target;
  ticket.fileName = leaf;
  ticket.fields.set('id', id);
  ticket.fields.set('updated', today());
  addHistoryLine(ticket, `перенумерован из ${old}: ${why}`);
  saveTicket(ticket);
  say(`${old} -> ${id}  ${leaf}`, 'green');
  // A mention of the old number may mean either ticket: the human or the agent decides, file by file.
  const mentions = git(['grep', '-l', '-w', old, '--', '.', ':!tickets/BOARD.md', `:!tickets/${old}-*`, `:!tickets/${id}-*`]).lines;
  if (mentions.length) {
    say(`     ${old} is mentioned in these files; point the ones about this ticket at ${id}:`);
    for (const m of mentions) say(`       ${m}`);
  }
}

function cmdRenumber() {
  const path = arg('path');
  if (path) {
    const file = isAbsolute(path) ? path : join(ROOT, path);
    if (!existsSync(file)) die(`no such ticket file: ${path}`);
    moveTicketNumber(readTicket(resolve(file)), 'номер совпал с другим тикетом');
    return;
  }
  /** @type {Map<string, Ticket[]>} */
  const byId = new Map();
  for (const t of allTickets()) {
    const id = t.fields.get('id');
    if (id !== undefined) byId.set(id, [...(byId.get(id) ?? []), t]);
  }
  const groups = [...byId].filter(([, list]) => list.length > 1);
  if (!groups.length) { say('no ticket number is used twice'); return; }
  let left = 0;
  for (const [id, list] of groups) {
    let local = list.filter(t => !onServer(t.path));
    let kept = list.filter(t => onServer(t.path));
    if (!kept.length) { kept = [local[0]]; local = local.slice(1); }
    if (kept.length > 1) {
      left++;
      say(`${id} is on the server twice: choose the one to move with -Path (${list.map(t => t.fileName).join(', ')})`, 'yellow');
      continue;
    }
    for (const t of local) moveTicketNumber(t, `номер совпал с ${kept[0].fileName}, который раньше попал на сервер`);
  }
  if (left) process.exit(1);
}

// ------------------------------------------------------------------ commands --

function cmdNew() {
  const title = arg('title'), type = arg('type');
  if (!title) die('missing -Title');
  if (!type) die(`missing -Type (${joinAllowed(TYPES)})`);
  if (!TYPES.includes(type)) die(`-Type must be one of: ${joinAllowed(TYPES)}`);
  const priority = arg('priority') || 'P2';
  if (!PRIORITIES.includes(priority)) die(`-Priority must be one of: ${joinAllowed(PRIORITIES)}`);
  const area = arg('area');
  if (!area) die(`missing -Area (${joinAllowed(AREAS)})`);
  if (!AREAS.includes(area)) die(`-Area must be one of: ${joinAllowed(AREAS)}`);
  const source = arg('source') || 'agent';
  if (!SOURCE_RE.test(source)) die('-Source must be agent, user or review-YYYY-MM-DD');
  const verify = arg('verifyin') || (type === 'bug' || type === 'feature' ? 'review' : 'none');
  if (!VERIFY.includes(verify)) die(`-VerifyIn must be one of: ${joinAllowed(VERIFY)}`);
  const status = arg('status') || (type === 'decision' ? 'accepted' : 'open');
  if (!STATUSES.includes(status)) die(`-Status must be one of: ${joinAllowed(STATUSES)}`);

  const id = newTicketId();
  const path = join(ticketsDir(), `${id}-${slugOf(arg('slug') || title)}.md`);
  const fields = new Map([['id', id], ['title', title], ['type', type], ['status', status], ['priority', priority], ['area', area],
    ['source', source], ['verify-in', verify], ['created', today()], ['updated', today()]]);
  for (const key of ['links', 'commits', 'labels']) if (arg(key)) fields.set(key, arg(key));
  /** @type {Ticket} */
  const ticket = { path, fileName: basename(path), fields, body: templateBody(type), parseErrors: [] };
  addHistoryLine(ticket, arg('note') || `создан (${type}, ${status}).`);
  saveTicket(ticket);
  process.stderr.write(`created ${id}\n`);
  console.log(path); // stdout, so callers can script against it
}

/** @param {string} existing @param {string} added @param {(s: string) => string} [norm] */
function mergeList(existing, added, norm = s => s) {
  const list = existing.split(',').map(s => s.trim()).filter(Boolean);
  for (const raw of added.split(',')) {
    const c = norm(raw.trim());
    if (c && !list.includes(c)) list.push(c);
  }
  return list.join(', ');
}

function cmdSet() {
  const ticket = findTicket(arg('id'));
  const f = ticket.fields;
  /** @type {string[]} */
  const changes = [];
  // Before the status: a status is checked against the type the ticket ends up with.
  const type = arg('type');
  if (type) {
    if (!TYPES.includes(type)) die(`-Type must be one of: ${joinAllowed(TYPES)}`);
    // The status must suit the new type (decision: accepted/superseded only): checkTicket refuses it otherwise.
    if (f.get('type') !== type) { changes.push(`type ${f.get('type')} -> ${type}`); f.set('type', type); }
  }
  const status = arg('status');
  if (status) {
    if (!STATUSES.includes(status)) die(`-Status must be one of: ${joinAllowed(STATUSES)}`);
    const old = f.get('status') ?? '';
    if (old !== status) {
      if (TERMINAL.includes(old) && !FORCE) die(`${f.get('id')} is ${old} (terminal). Re-open only with -Force.`);
      if (f.get('type') === 'decision' && !DECISION.includes(status)) die(`decision tickets may only be ${joinAllowed(DECISION)}`);
      if (f.get('type') !== 'decision' && DECISION.includes(status)) die(`status '${status}' is only for decision tickets`);
      f.set('status', status);
      changes.push(`status ${old} -> ${status}`);
    }
  }
  for (const [key, allowed, label] of /** @type {[string, string[], string][]} */ ([['priority', PRIORITIES, 'Priority'], ['area', AREAS, 'Area']])) {
    const value = arg(key);
    if (!value) continue;
    if (!allowed.includes(value)) die(`-${label} must be one of: ${joinAllowed(allowed)}`);
    if (f.get(key) !== value) { changes.push(`${key} ${f.get(key)} -> ${value}`); f.set(key, value); }
  }
  const verify = arg('verifyin');
  if (verify) {
    if (!VERIFY.includes(verify)) die(`-VerifyIn must be one of: ${joinAllowed(VERIFY)}`);
    f.set('verify-in', verify);
    changes.push(`verify-in -> ${verify}`);
  }
  if (arg('title')) { f.set('title', arg('title')); changes.push('title updated'); }
  if (arg('commits')) { f.set('commits', mergeList(f.get('commits') ?? '', arg('commits'))); changes.push(`commits ${f.get('commits')}`); }
  if (arg('links')) { f.set('links', mergeList(f.get('links') ?? '', arg('links'), s => s.toUpperCase())); changes.push(`links ${f.get('links')}`); }
  if (arg('labels')) { f.set('labels', arg('labels')); changes.push(`labels ${arg('labels')}`); }
  const note = arg('note');
  if (!changes.length && !note) die('nothing to change: pass -Status/-Type/-Priority/-Area/-VerifyIn/-Title/-Commits/-Links/-Labels or -Note');

  let line = changes.join('; ');
  if (note) line = line ? `${line} - ${note}` : note;
  addHistoryLine(ticket, line);
  f.set('updated', today());
  const problems = checkTicket(ticket);
  if (problems.length) {
    say(`refusing to save ${f.get('id')}:`, 'red');
    for (const p of problems) say(`  - ${p}`);
    process.exit(1);
  }
  saveTicket(ticket);
  say(`${f.get('id')}: ${line}`, 'green');
}

/** By priority, then by age. @param {Ticket} a @param {Ticket} b */
const byPriority = (a, b) => cmp(a.fields.get('priority'), b.fields.get('priority')) || idNumber(a.fields.get('id')) - idNumber(b.fields.get('id'));
/** @param {string | undefined} a @param {string | undefined} b */
const cmp = (a, b) => ((a ?? '') < (b ?? '') ? -1 : (a ?? '') > (b ?? '') ? 1 : 0);

function cmdList() {
  const status = arg('status'), type = arg('type'), area = arg('area'), priority = arg('priority');
  const tickets = allTickets().filter(t => {
    const f = t.fields;
    if (!f.size) return false;
    if (status ? f.get('status') !== status : !ACTIVE.includes(f.get('status') ?? '') && f.get('status') !== 'accepted') return false;
    return (!type || f.get('type') === type) && (!area || f.get('area') === area) && (!priority || f.get('priority') === priority);
  }).sort(byPriority);
  if (!tickets.length) { say('no tickets match'); return; }
  /** @param {(string | undefined)[]} c */
  const row = c => `${(c[0] ?? '').padEnd(10)} ${(c[1] ?? '').padEnd(3)} ${(c[2] ?? '').padEnd(16)} ${(c[3] ?? '').padEnd(11)} ${c[4] ?? ''}`;
  say(row(['ID', 'P', 'STATUS', 'AREA', 'TITLE']));
  say('-'.repeat(100));
  for (const t of tickets) say(row(['id', 'priority', 'status', 'area', 'title'].map(k => t.fields.get(k))));
  say('');
  say(`${tickets.length} ticket(s)`);
}

function checklistLines() {
  const pending = allTickets().filter(t => t.fields.size && t.fields.get('status') === 'fixed');
  if (!pending.length) return ['_Нечего проверять: тикетов в статусе `fixed` нет._'];
  /** @type {string[]} */
  const lines = [];
  const groups = [['review', 'Проверить ГЛАЗАМИ В ДЕМО'], ['build', 'Проверить БИЛДОМ'], ['test', 'Проверить ТЕСТОМ'], ['bench', 'Проверить ЗАМЕРОМ (bench)'], ['none', 'Без проверки (можно закрывать)']];
  for (const [key, caption] of groups) {
    const items = pending.filter(t => (t.fields.get('verify-in') ?? '') === key);
    if (!items.length) continue;
    lines.push(`**${caption}**`, '');
    for (const t of items.sort(byPriority)) {
      const f = t.fields;
      lines.push(`- [ ] [${f.get('id')}](${t.fileName}) \`[${f.get('priority')}/${f.get('area')}]\` ${f.get('title')}`);
    }
    lines.push('');
  }
  return lines;
}

function feedbackPath() {
  // Where the board UI can realistically have put the file: next to the tickets (File System Access API, the user
  // picks it once) or in Downloads (the plain download fallback). An explicit -Path always wins.
  const path = arg('path');
  if (path) {
    if (!existsSync(path)) die(`no feedback file at '${path}'`);
    return resolve(path);
  }
  const candidates = [join(ticketsDir(), 'feedback.json'), join(homedir(), 'Downloads', 'feedback.json')];
  const found = candidates.find(c => existsSync(c));
  if (!found) die(`no feedback file found. Save it from board.html (the button 'Сохранить фидбек в файл'), or pass -Path. Looked in: ${candidates.join('; ')}`);
  return found;
}

// Applies a review report saved from board.html: verdicts, notes and new items.
// - the file is ARCHIVED after a successful run, so the same verdicts cannot be applied twice by accident;
// - an unknown ticket id is reported and skipped instead of aborting the batch: a stale UI must not block the rest;
// - 'verified' is the one status the agent may never decide alone, and this file is where the user's verdict arrives.
function cmdApply() {
  const file = feedbackPath();
  /** @type {{verdicts?: Record<string, string>, notes?: Record<string, string>, feedback?: {title?: string, type?: string, area?: string, prio?: string, desc?: string}[]}} */
  let report;
  try { report = JSON.parse(readText(file)); } catch (err) { die(`could not parse '${file}': ${err}`); }
  const stamp = today();
  let applied = 0, created = 0;
  /** @type {string[]} */
  const skipped = [];
  // Every spelling the UI or a hand-written report can produce: 'fail' is the one the board stores. Keep the aliases.
  /** @type {Record<string, string>} */
  const VERDICTS = { ok: 'verified', verified: 'verified', pass: 'verified', fail: 'open', bad: 'open', open: 'open' };
  for (const [id, raw] of Object.entries(report.verdicts ?? {})) {
    const verdict = String(raw ?? '');
    if (!verdict.trim()) continue;
    const ticket = allTickets().find(t => t.fields.get('id') === id);
    if (!ticket) { skipped.push(`${id} (no such ticket)`); continue; }
    const status = VERDICTS[verdict.toLowerCase()];
    if (!status) { skipped.push(`${id} (verdict '${verdict}')`); continue; }
    const note = String(report.notes?.[id] ?? '');
    let line = status === 'verified' ? `ревью ${stamp}: фикс работает` : `ревью ${stamp}: фикс НЕ помог, проблема осталась`;
    if (note.trim()) line = `${line} — ${note}`;
    ticket.fields.set('status', status);
    addHistoryLine(ticket, line);
    saveTicket(ticket);
    say(`${id}: -> ${status}`, 'green');
    applied++;
  }
  for (const item of report.feedback ?? []) {
    const title = String(item.title ?? '');
    if (!title.trim()) continue;
    const id = newTicketId();
    const type = String(item.type ?? '').trim() || 'bug';
    const area = String(item.area ?? '').trim() || 'app';
    const priority = String(item.prio ?? '').trim() || 'P2';
    const verify = ['tech', 'decision', 'question'].includes(type) ? 'none' : 'review';
    const status = type === 'decision' ? 'accepted' : 'open';
    const path = join(ticketsDir(), `${id}-${slugOf(title)}.md`);
    const fields = new Map([['id', id], ['title', title], ['type', type], ['status', status], ['priority', priority], ['area', area],
      ['source', `review-${stamp}`], ['verify-in', verify], ['created', stamp], ['updated', stamp]]);
    /** @type {Ticket} */
    const ticket = { path, fileName: basename(path), fields, body: templateBody(type), parseErrors: [] };
    const desc = String(item.desc ?? '');
    addHistoryLine(ticket, desc.trim() ? `из фидбека ревью ${stamp}: ${desc}` : `из фидбека ревью ${stamp}`);
    saveTicket(ticket);
    say(`created ${id}: ${title}`, 'green');
    created++;
  }
  for (const problem of skipped) say(`skipped ${problem}`, 'yellow');
  if (!applied && !created) { say('nothing to apply (no verdicts, no new items)', 'yellow'); return; }
  const archive = join(ticketsDir(), 'feedback.applied.json');
  renameSync(file, archive);
  say(`applied ${applied} verdict(s), created ${created} ticket(s); report archived to ${archive}`, 'cyan');
  cmdBoard();
}

function cmdBoard() {
  const valid = allTickets().filter(t => t.fields.size && t.fields.has('status'));
  /** @param {Ticket} t @param {string} k */
  const g = (t, k) => t.fields.get(k) ?? '';
  const lines = ['<!-- GENERATED by tools/tickets.mjs board - НЕ РЕДАКТИРОВАТЬ РУКАМИ, правь тикеты -->', '', '# Доска тикетов XMB-лаунчера', '',
    `Сгенерировано ${today()}. Формат и процессы — [tickets/README.md](README.md).`, ''];

  // 1. Summary
  lines.push('## Сводка', '', '| Статус | Тикетов |', '|---|---|');
  for (const s of STATUSES) {
    const n = valid.filter(t => g(t, 'status') === s).length;
    if (n) lines.push(`| \`${s}\` | ${n} |`);
  }
  lines.push(`| **всего** | **${valid.length}** |`, '');
  const areaRows = AREAS.flatMap(a => {
    const of = valid.filter(t => g(t, 'area') === a);
    const [o, p, fx] = ['open', 'in-progress', 'fixed'].map(s => of.filter(t => g(t, 'status') === s).length);
    return o + p + fx ? [`| ${a} | ${o} | ${p} | ${fx} |`] : [];
  });
  if (areaRows.length) lines.push('| Область | open | in-progress | fixed |', '|---|---|---|---|', ...areaRows, '');

  // 2. Open by priority
  lines.push('## 🔥 Открытые (open / in-progress)', '');
  const open = valid.filter(t => ['open', 'in-progress'].includes(g(t, 'status')));
  if (!open.length) lines.push('_Пусто._', '');
  else {
    lines.push('| ID | P | Статус | Область | Заголовок |', '|---|---|---|---|---|');
    for (const t of open.sort(byPriority)) {
      const mark = /(^|,\s*)watch(\s*,|$)/.test(g(t, 'labels')) ? ' 👀' : '';
      lines.push(`| [${g(t, 'id')}](${t.fileName}) | ${g(t, 'priority')} | ${g(t, 'status')} | ${g(t, 'area')} | ${g(t, 'title')}${mark} |`);
    }
    lines.push('');
  }

  // 3. Waiting for verification
  lines.push('## 🧪 Ждут проверки (fixed)', '', 'Агент считает, что починил; вы это не проверяли. Проверить надо одно: проблема действительно ушла?', '',
    ...checklistLines());

  // 4. Decisions
  lines.push('## 📐 Решения (decision)', '');
  const decisions = valid.filter(t => g(t, 'type') === 'decision');
  if (!decisions.length) lines.push('_Пока нет._', '');
  else {
    lines.push('| ID | Принято | Статус | Область | Решение |', '|---|---|---|---|---|');
    for (const t of decisions.sort((a, b) => cmp(g(b, 'created'), g(a, 'created')) || idNumber(g(b, 'id')) - idNumber(g(a, 'id')))) {
      lines.push(`| [${g(t, 'id')}](${t.fileName}) | ${g(t, 'created')} | ${g(t, 'status')} | ${g(t, 'area')} | ${g(t, 'title')} |`);
    }
    lines.push('');
  }

  // 5. Recently closed: updated on a day no earlier than 14 days before this moment
  lines.push('## ✅ Недавно закрытые (14 дней)', '');
  const cutoff = Date.now() - 14 * 864e5;
  const recent = valid.filter(t => {
    if (!['verified', 'closed', 'wontfix', 'cannot-reproduce'].includes(g(t, 'status'))) return false;
    const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(g(t, 'updated'));
    return m !== null && new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3])).getTime() >= cutoff;
  });
  if (!recent.length) lines.push('_Пусто._', '');
  else {
    lines.push('| ID | Обновлён | Статус | Заголовок |', '|---|---|---|---|');
    for (const t of recent.sort((a, b) => cmp(g(b, 'updated'), g(a, 'updated')) || idNumber(g(b, 'id')) - idNumber(g(a, 'id')))) {
      lines.push(`| [${g(t, 'id')}](${t.fileName}) | ${g(t, 'updated')} | ${g(t, 'status')} | ${g(t, 'title')} |`);
    }
    lines.push('');
  }

  const path = join(ticketsDir(), 'BOARD.md');
  writeText(path, `${lines.join('\n').trimEnd()}\n`);
  say(`board written: ${path} (${valid.length} tickets)`, 'green');
  cmdHtml(); // keep the browser view in sync with the markdown board
}

function cmdHtml() {
  const data = allTickets().filter(t => t.fields.size && t.fields.has('status')).map(t => {
    const g = (/** @type {string} */ k) => t.fields.get(k) ?? '';
    return { id: g('id'), title: g('title'), type: g('type'), status: g('status'), priority: g('priority'), area: g('area'), source: g('source'),
      verify: g('verify-in'), created: g('created'), updated: g('updated'), links: g('links'), commits: g('commits'), labels: g('labels'),
      file: t.fileName, body: t.body.trim() };
  });
  // Inside <script>: a ticket body saying "</script>" must not end it.
  const json = JSON.stringify(data).replace(/[<>&\u2028\u2029]/g, c => `\\u${c.charCodeAt(0).toString(16).padStart(4, '0')}`);
  const html = readText(join(TOOLS, 'tickets_board.html')).replace('/*__DATA__*/null', () => json).replace('__GENERATED__', today());
  const path = join(ticketsDir(), 'board.html');
  writeText(path, html);
  say(`html written: ${path} (${data.length} tickets)`, 'green');
}

function cmdHelp() {
  say(`tickets.mjs - file-based tickets for the XMB launcher (node tools/tickets.mjs <command>, or tools/tickets.ps1 / tools/tickets.sh)

  new       -Type <bug|feature|tech|decision|question> -Title "..." [-Priority P0..P3]
            -Area <math|world|nav|sim|net|mesh|app|design|proto|docs|infra>
            [-Source agent|user|review-YYYY-MM-DD] [-VerifyIn review|test|build|bench|none]
            [-Status ...] [-Slug ...] [-Links ...] [-Labels ...] [-Note "первая строка истории"]

  set       -Id LNC-A0042 [-Status ...] [-Type ...] [-Priority ...] [-Area ...] [-VerifyIn ...]
            [-Title "..."] [-Commits abc1234] [-Links LNC-A0001] [-Labels watch]
            [-Note "что произошло"] [-Force]

  list      [-Status ...] [-Type ...] [-Area ...] [-Priority ...]   (без фильтров: активные)
  checklist                                                          что проверить (fixed)
  board                                                              перегенерировать BOARD.md + board.html
  html                                                               только tickets/board.html (UI в браузере)
  validate                                                           проверить все тикеты
  renumber  [-Path tickets/LNC-A0042-....md]                        дубль номера после слияния веток: без -Path
                                                                     новый номер получает копия, которой нет на сервере
  apply     [-Path tickets/feedback.json]                            применить фидбек из board.html
                                                                     (кнопка «Сохранить фидбек в файл»)

Правила и процессы: tickets/README.md`);
}

// --------------------------------------------------------------------- main --

/** @type {Record<string, () => void>} */
const COMMANDS = { new: cmdNew, set: cmdSet, list: cmdList, checklist: () => checklistLines().forEach(l => say(l)), board: cmdBoard, html: cmdHtml,
  validate: cmdValidate, renumber: cmdRenumber, apply: cmdApply, help: cmdHelp };
const run = COMMANDS[COMMAND.toLowerCase()];
if (!run) {
  say(`unknown command '${COMMAND}'`);
  cmdHelp();
  process.exit(1);
}
run();
