// Seal one file into a self-contained page: the file is embedded AES-256-GCM encrypted,
// the key goes only into the link fragment.
// Usage: node seal.mjs <file> <template.html> [out.html] [key]
// Pass the previous key to reseal without breaking links already sent.
import { readFileSync, writeFileSync } from 'node:fs';
import { basename } from 'node:path';
import { execFileSync } from 'node:child_process';

const [path, template, out = 'sealed.html', oldKey] = process.argv.slice(2);
// Artifact downloads only allow certain types (.apk is not one), so ship a real zip holding the file.
const name = basename(path).replace(/\.[^.]*$/, '') + '.zip';
const data = execFileSync('zip', ['-jq', '-', path]);
const key = oldKey ? new Uint8Array(Buffer.from(oldKey, 'base64url')) : crypto.getRandomValues(new Uint8Array(32));
const iv = crypto.getRandomValues(new Uint8Array(12));
const ck = await crypto.subtle.importKey('raw', key, 'AES-GCM', false, ['encrypt']);
const ct = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, ck, data));
const sealed = Buffer.concat([iv, ct]).toString('base64');
const b64u = Buffer.from(key).toString('base64url');

const html = readFileSync(template, 'utf8')
  .replace('__NAME__', JSON.stringify(name))
  .replace('__SIZE__', String(Math.round(data.length / 1024)))
  .replace('__DATA__', sealed);
writeFileSync(out, html);
console.log(out, '-> append to the page URL: #' + b64u);
