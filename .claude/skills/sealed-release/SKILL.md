---
name: sealed-release
description: Prepare a "sealed release link" for a built app or file (APK and the like) — one install page published as a claude.ai Artifact with the file embedded AES-256-GCM encrypted and the key only in the link after '#', so the page is useless without the full link. Styled as a conversion-focused install page (pitch, one download button, install steps, trust facts). Use when the user asks for a release/download/install link for an APK or build, "upload it and give me a link", a link they can send in Telegram, or to update/reseal an existing sealed release page.
---

# Sealed release link

Result: `https://claude.ai/artifact/<id>#<43-char key>`. The page carries the file encrypted;
the key travels only in the fragment (never sent to any server). Without the `#key` the page
shows "link incomplete" and nothing can be decrypted.

Bundled here:
- `seal.mjs` — `node seal.mjs <file> <template.html> [out.html] [key]`: zips the file, encrypts it,
  fills the template's `__NAME__` / `__SIZE__` / `__DATA__`, prints the `#key`.
  Pass `key` to reseal with an existing key.
- `example.template.html` — a finished page (XMB launcher, PS3 look) to copy the structure and
  decrypt/download script from.

## Steps

1. **Build the file** if needed. Android/Gradle: old Gradle (≤8.x) fails on JDK 25 — use a JDK 17/21
   (`~/.sdkman/candidates/java/21.*`) via `JAVA_HOME`; write `local.properties` with
   `sdk.dir=$HOME/Library/Android/sdk` if missing; `gradlew` may lack +x → `sh ./gradlew assembleRelease`.
2. **Gather true facts for the page** — never invent: app name and one-line pitch (README),
   min Android version (`minSdk`), permissions (`AndroidManifest.xml`; "no INTERNET permission"
   is a strong trust point when true), source repo URL, signing (debug-signed = sideload only).
3. **Write a template for this app**: copy `example.template.html` into the working folder and
   rewrite title, copy, install steps, facts and visual theme to fit the subject (follow the
   artifact page contract: no doctype, tokens, phone width). Keep the `<script>` unlock/download
   block and the three placeholders exactly as they are. Install steps must match reality: the
   download is a `.zip` → open it → tap the `.apk` → allow installs from this source → (for a
   launcher: set as default Home).
4. **Seal**: `node ~/.claude/skills/sealed-release/seal.mjs <file> <template> <out.html>`.
   Optional check: decrypt the `SEALED` blob in node with the key and `cmp` the zipped file.
5. **Publish** `<out.html>` with the Artifact tool, `capabilities: {"downloads": true}`, an icon,
   a one-line description. Give the user the full link with `#key`.
6. Tell the user: it is private until they share it from the page's **Share** menu (Claude cannot
   change sharing); send the link including everything after `#`.

## Updating an already-shared page

Viewers get republished versions immediately, so a new key breaks every link already sent.
Reseal with the existing key (4th arg) and republish the same file path / artifact URL.
Find the key in the earlier link the user shared.

## Why it is built this way (don't relitigate)

- **Artifact downloads allow only listed extensions** (zip, pdf, png, …; not apk) and plain
  `<a download>` links are blocked in the viewer → the file ships as a real `.zip` saved via
  `claude.use("downloads").save()`. Off claude.ai (any static host) the same page falls back to
  a normal download.
- **Only a plain `#token` reaches `location.hash` in an artifact** (letters, digits, `.`, `_`, `~`, `-`)
  → the key is base64url, no `=` or `/`.
- **A link can't carry the file itself**: Telegram caps messages at 4096 chars; a 115 KB APK is
  ~73k–92k chars even with xz + dense encoding, and dictionaries gain ~3% on R8-shrunk DEX.
  A longer key adds no security over 256 bits.
- **Public hosts are blocked in auto mode** (temp file hosts, `gh repo create --public` + Pages).
  If the user wants a real `.apk` download on GitHub Pages/Netlify, give them the command to run
  with `!` instead of trying it.
