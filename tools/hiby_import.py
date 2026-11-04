"""
Перенос части треков с плеера HiBy R3II (прошивка r3ii-xmb) на телефон — LNC-A0020.

Карта плеера (подключён как диск): .xmb/ratings.tsv, Playlists/*.m3u8. Берутся треки из плейлистов, с оценкой
4–5 звёзд и прослушанные 2+ раза (LNC-A0027). Файлы — в /sdcard/Music/HiBy/<путь на карте>, трек из CUE-образа
вырезается в отдельный FLAC (без потерь, с тегами и обложкой). Оценки и плейлисты в формате плеера, но с путями
телефона, и таблица соответствия путей (для обратной синхронизации, LNC-A0025) — в данные лаунчера:
/sdcard/Android/data/<пакет>/files/hiby/.

    python tools/hiby_import.py --card H: [--serial ...] [--package dev.anded.xmb] [--dry-run]

Нужны adb (или переменная ADB) и ffmpeg в PATH. Уже скопированные файлы того же размера не копируются заново.
"""
import argparse
import os
import posixpath
import re
import subprocess
import sys
import tempfile
import time
import urllib.parse

PHONE_ROOT = '/sdcard/Music/HiBy'
RATINGS_HEADER = '#xmb-ratings 1\n#путь\tначало\tзвёзды\tпрослушан\tпропущен\tпоследний раз\tисполнитель\tальбом\tназвание\n'
COVERS = ('cover.jpg', 'folder.jpg', 'cover.png', 'folder.png', 'front.jpg')


def read_ratings(card):
    rows = {}
    with open(os.path.join(card, '.xmb', 'ratings.tsv'), encoding='utf-8') as f:
        for line in f:
            if line.startswith('#') or not line.strip():
                continue
            r = line.rstrip('\n').split('\t')
            rows[(r[0], round(float(r[1]), 2))] = r
    return rows


def read_playlists(card):
    """Плейлист → [(путь от корня карты, начало, заголовок из EXTINF)]; «Избранное» первым."""
    out = {}
    d = os.path.join(card, 'Playlists')
    for name in sorted(os.listdir(d)):
        if not name.endswith('.m3u8'):
            continue
        items, start, title = [], 0.0, None
        with open(os.path.join(d, name), encoding='utf-8-sig') as f:
            for line in f:
                line = line.strip()
                if line.startswith('#EXTINF:'):
                    title = line.split(',', 1)[1] if ',' in line else None
                elif line.startswith('#XMB-START:'):
                    start = float(line.split(':', 1)[1])
                elif line and not line.startswith('#'):
                    items.append((posixpath.normpath(posixpath.join('Playlists', line)), round(start, 2), title))
                    start, title = 0.0, None
        out[name[:-5]] = items
    return out


def parse_cue(path):
    """Треки CUE: [(номер, начало, название, исполнитель)] и файл образа."""
    raw = open(path, 'rb').read()
    for enc in ('utf-8-sig', 'cp1251', 'latin-1'):
        try:
            text = raw.decode(enc)
            break
        except UnicodeDecodeError:
            continue
    tracks, image, cur, performer = [], None, None, None
    for line in text.splitlines():
        s = line.strip()
        if s.startswith('FILE '):
            m = re.match(r'FILE\s+"?(.+?)"?\s+\w+$', s)
            image = m.group(1) if m else image
        elif s.startswith('PERFORMER ') and cur is None:
            performer = s[10:].strip('"')
        elif s.startswith('TRACK '):
            cur = {'n': int(s.split()[1]), 'title': '', 'performer': performer, 'start': None}
            tracks.append(cur)
        elif cur is not None and s.startswith('TITLE '):
            cur['title'] = s[6:].strip('"')
        elif cur is not None and s.startswith('PERFORMER '):
            cur['performer'] = s[10:].strip('"')
        elif cur is not None and s.startswith('INDEX 01 '):
            m, sec, fr = (int(x) for x in s[9:].split(':'))
            cur['start'] = m * 60 + sec + fr / 75.0
    return image, tracks


def cue_track(card, image_rel, start):
    """Для трека образа: номер, название, исполнитель, конец (None — до конца файла)."""
    d = os.path.join(card, posixpath.dirname(image_rel))
    base = posixpath.basename(image_rel)
    for name in os.listdir(d):
        if not name.lower().endswith('.cue'):
            continue
        image, tracks = parse_cue(os.path.join(d, name))
        if image is None or os.path.basename(image).lower() != base.lower():
            continue
        for i, t in enumerate(tracks):
            if t['start'] is not None and abs(t['start'] - start) < 0.05:
                end = tracks[i + 1]['start'] if i + 1 < len(tracks) else None
                return t, end
    return None, None


def safe(name):
    return re.sub(r'[\\/:*?"<>|]', '_', name).strip() or 'track'


def adb(args, serial, **kw):
    exe = os.environ.get('ADB', 'adb')
    cmd = [exe] + (['-s', serial] if serial else []) + args
    return subprocess.run(cmd, capture_output=True, text=True, encoding='utf-8', errors='replace', **kw)


def q(path):
    """Путь для adb shell: команда проходит через шелл телефона, пробелы и скобки в именах — в кавычках."""
    return "'" + path.replace("'", "'\\''") + "'"


def remote_size(path, serial):
    r = adb(['shell', 'stat', '-c', '%s', q(path)], serial)
    return int(r.stdout.strip()) if r.returncode == 0 and r.stdout.strip().isdigit() else -1


def scan(rel, serial):
    """Файл — в MediaStore: adb push идёт мимо него."""
    adb(['shell', 'am', 'broadcast', '-a', 'android.intent.action.MEDIA_SCANNER_SCAN_FILE',
         '-d', q('file://' + urllib.parse.quote(f'{PHONE_ROOT}/{rel}'))], serial)


def push(local, remote, serial, dry):
    if remote_size(remote, serial) == os.path.getsize(local):
        return False
    if dry:
        return True
    adb(['shell', 'mkdir', '-p', q(posixpath.dirname(remote))], serial)
    r = adb(['push', local, remote], serial)
    if r.returncode != 0:
        sys.exit(f'adb push: {r.stderr.strip()}')
    return True


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--card', required=True, help='диск карты плеера, например H:')
    ap.add_argument('--serial')
    ap.add_argument('--package', default='dev.anded.xmb')
    ap.add_argument('--dry-run', action='store_true')
    ap.add_argument('--replace-data', action='store_true',
                    help='заменить оценки и плейлисты лаунчера данными плеера (то, что изменили на телефоне, пропадёт)')
    a = ap.parse_args()
    card = a.card.rstrip('\\/') + '/'

    ratings = read_ratings(card)
    playlists = read_playlists(card)

    # Отбор: плейлисты, 4–5 звёзд, прослушано 2+ раза; подкасты — нет.
    chosen = {}
    for items in playlists.values():
        for path, start, _ in items:
            chosen[(path, start)] = True
    for (path, start), r in ratings.items():
        if int(r[2]) >= 4 or int(r[3]) >= 2:
            chosen[(path, start)] = True
    chosen = [k for k in chosen if not k[0].startswith('Podcasts/') and os.path.exists(card + k[0])]

    # Треки одного образа — несколько; образ «отдельным треком» не считается.
    images = {}
    for path, start in chosen:
        images.setdefault(path, set()).add(start)
    cue_paths = {p for p, starts in images.items() if starts != {0.0} or (p.lower().endswith('.flac') and cue_track(card, p, 0.0)[0])}

    phone = {}   # (путь на карте, начало) → путь на телефоне от Music/HiBy
    tmp = tempfile.mkdtemp(prefix='hiby_')
    copied = skipped = 0
    for path, start in sorted(chosen):
        if path in cue_paths:
            t, end = cue_track(card, path, start)
            if t is None:
                print('нет трека в CUE:', path, start)
                continue
            rel = posixpath.join(posixpath.dirname(path), f"{t['n']:02d}. {safe(t['title'])}.flac")
            out = os.path.join(tmp, f"{len(phone)}.flac")
            r = ratings.get((path, start))
            album = r[7] if r else posixpath.basename(posixpath.dirname(path))
            cover = next((os.path.join(card, posixpath.dirname(path), c) for c in COVERS
                          if os.path.exists(os.path.join(card, posixpath.dirname(path), c))), None)
            cmd = ['ffmpeg', '-v', 'error', '-y', '-ss', f'{start:.3f}'] + (['-to', f'{end:.3f}'] if end else []) + \
                  ['-i', card + path]
            if cover:
                cmd += ['-i', cover, '-map', '0:a', '-map', '1:v', '-c:v', 'copy', '-disposition:v', 'attached_pic']
            else:
                cmd += ['-map', '0:a']
            cmd += ['-c:a', 'flac', '-map_metadata', '-1', '-metadata', f"title={t['title']}",
                    '-metadata', f"artist={t['performer'] or (r[6] if r else '')}", '-metadata', f'album={album}',
                    '-metadata', f"track={t['n']}", out]
            if not a.dry_run:
                res = subprocess.run(cmd, capture_output=True, text=True, encoding='utf-8', errors='replace')
                if res.returncode != 0:
                    print('ffmpeg:', path, res.stderr.strip()[:300])
                    continue
            local = out
        else:
            rel, local = path, card + path
        phone[(path, start)] = rel
        remote = f'{PHONE_ROOT}/{rel}'
        if not os.path.exists(local):   # --dry-run: трек образа не вырезан
            copied += 1
            print('✂', rel)
        else:
            if push(local, remote, a.serial, a.dry_run):
                copied += 1
                print('→', rel)
            else:
                skipped += 1
        # Обложка папки — рядом, если есть (для просмотра файлов; MediaStore берёт обложку из тегов).
        for c in COVERS:
            src = os.path.join(card, posixpath.dirname(path), c)
            if os.path.exists(src):
                push(src, f'{PHONE_ROOT}/{posixpath.dirname(rel)}/{c}', a.serial, a.dry_run)
                break

    # Данные лаунчера: оценки, плейлисты, соответствие путей.
    data = os.path.join(tmp, 'hiby')
    os.makedirs(os.path.join(data, 'Playlists'), exist_ok=True)
    with open(os.path.join(data, 'ratings.tsv'), 'w', encoding='utf-8', newline='\n') as f:
        f.write(RATINGS_HEADER)
        for key, rel in sorted(phone.items(), key=lambda kv: kv[1]):
            r = ratings.get(key)
            if r:
                f.write('\t'.join([rel, '0.000'] + r[2:]) + '\n')
    with open(os.path.join(data, 'source.tsv'), 'w', encoding='utf-8', newline='\n') as f:
        f.write('#xmb-source 1\n#путь на телефоне\tпуть на карте плеера\tначало\n')
        for (path, start), rel in sorted(phone.items(), key=lambda kv: kv[1]):
            f.write(f'{rel}\t{path}\t{start:.3f}\n')
    for name, items in playlists.items():
        with open(os.path.join(data, 'Playlists', name + '.m3u8'), 'w', encoding='utf-8', newline='\n') as f:
            f.write('#EXTM3U\n')
            for path, start, title in items:
                rel = phone.get((path, start))
                if rel is None:
                    continue
                if title:
                    f.write(f'#EXTINF:-1,{title}\n')
                f.write(f'../{rel}\n')
    base = f'/sdcard/Android/data/{a.package}/files/hiby'
    # Оценки и плейлисты на телефоне живут своей жизнью: второй запуск их не затирает (слияние — LNC-A0025).
    keep = remote_size(base + '/ratings.tsv', a.serial) >= 0 and not a.replace_data
    if keep:
        print('данные лаунчера уже есть — не заменяю (--replace-data, чтобы заменить)')
    if not a.dry_run and not keep:
        adb(['shell', 'mkdir', '-p', base + '/Playlists'], a.serial)
        for root, _, files in os.walk(data):
            for fn in files:
                local = os.path.join(root, fn)
                rel = os.path.relpath(local, data).replace('\\', '/')
                adb(['push', local, f'{base}/{rel}'], a.serial)
        print('оценки и плейлисты — в лаунчер; он прочитает их при следующем запуске')
    if not a.dry_run:
        for rel in sorted(set(phone.values())):
            scan(rel, a.serial)
        # Файл, отсканированный, пока он ещё писался, застревает «в обработке» (is_pending) — приложения его не видят.
        time.sleep(3)
        r = adb(['shell', 'content', 'query', '--uri', 'content://media/external/audio/media', '--projection',
                 'relative_path:_display_name', '--where', q("relative_path like 'Music/HiBy/%' and is_pending=1")], a.serial)
        for m in re.finditer(r'relative_path=Music/HiBy/(.*?), _display_name=(.*)', r.stdout):
            print('пересканирован:', m.group(1) + m.group(2).strip())
            scan(m.group(1) + m.group(2).strip(), a.serial)
    print(f'треков: {len(phone)}, скопировано {copied}, уже были {skipped}; плейлистов {len(playlists)}')


if __name__ == '__main__':
    main()
