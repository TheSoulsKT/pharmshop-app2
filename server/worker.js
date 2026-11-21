// Сервер чата поддержки PharmShop — Cloudflare Workers + D1.
// Настройки (Variables and Secrets): ADMIN_CODE (обязательно), BOT_TOKEN и CHAT_ID (по желанию, для уведомлений в Telegram).
// Привязка базы: переменная DB -> ваша D1-база.

const SCHEMA = [
  `CREATE TABLE IF NOT EXISTS threads (
     device TEXT PRIMARY KEY, secret_hash TEXT NOT NULL, nick TEXT DEFAULT '', last_order TEXT DEFAULT '',
     last_text TEXT DEFAULT '', last_ts INTEGER DEFAULT 0, last_user_ts INTEGER DEFAULT 0,
     unread INTEGER DEFAULT 0, created INTEGER DEFAULT 0)`,
  `CREATE TABLE IF NOT EXISTS messages (
     id INTEGER PRIMARY KEY AUTOINCREMENT, device TEXT NOT NULL, sender TEXT NOT NULL, text TEXT NOT NULL, ts INTEGER NOT NULL)`,
  `CREATE INDEX IF NOT EXISTS idx_messages_device ON messages (device, id)`,
  `CREATE TABLE IF NOT EXISTS fails (ip TEXT PRIMARY KEY, n INTEGER, ts INTEGER)`,
  // блокировки спамеров: kind = 'device' | 'ip'
  `CREATE TABLE IF NOT EXISTS blocks (
     kind TEXT NOT NULL, value TEXT NOT NULL, reason TEXT DEFAULT '', ts INTEGER NOT NULL, PRIMARY KEY (kind, value))`,
  // удалённые настройки приложения (одна запись k='app' с JSON)
  `CREATE TABLE IF NOT EXISTS settings (k TEXT PRIMARY KEY, v TEXT NOT NULL)`,
  // сессии админа: хранится только хэш токена; code_fp — отпечаток ADMIN_CODE (смена кода гасит все сессии)
  `CREATE TABLE IF NOT EXISTS sessions (
     hash TEXT PRIMARY KEY, code_fp TEXT NOT NULL, created INTEGER NOT NULL, exp INTEGER NOT NULL)`,
];
// Новые колонки для уже существующей базы (ошибка «колонка уже есть» игнорируется).
const MIGRATIONS = [
  `ALTER TABLE messages ADD COLUMN image TEXT DEFAULT ''`,
  `ALTER TABLE messages ADD COLUMN ip TEXT DEFAULT ''`,
  `ALTER TABLE messages ADD COLUMN dev TEXT DEFAULT ''`,
  `ALTER TABLE messages ADD COLUMN geo TEXT DEFAULT ''`,
  `ALTER TABLE threads ADD COLUMN last_ip TEXT DEFAULT ''`,
  `ALTER TABLE threads ADD COLUMN last_dev TEXT DEFAULT ''`,
  `ALTER TABLE threads ADD COLUMN status TEXT DEFAULT 'new'`,          // new | work | done
  `ALTER TABLE threads ADD COLUMN admin_read_ts INTEGER DEFAULT 0`,    // админ прочитал сообщения пользователя до этого времени
  `ALTER TABLE threads ADD COLUMN user_read_ts INTEGER DEFAULT 0`,     // пользователь прочитал ответы админа до этого времени
  `CREATE INDEX IF NOT EXISTS idx_messages_ip ON messages (ip)`,
  `CREATE INDEX IF NOT EXISTS idx_threads_ip ON threads (last_ip, created)`,
];
const MAX_IMAGE = 1_400_000; // символов base64 (~1 МБ картинки); лимит строки в D1 — 2 МБ
const B64 = /^[A-Za-z0-9+/=]+$/;
let ready = false;

const json = (o, s = 200) =>
  new Response(JSON.stringify(o), {
    status: s,
    headers: { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' },
  });
const err = (m, s = 400) => json({ error: m }, s);
const UUID = /^[0-9a-f-]{36}$/;
const LOCK_MS = 15 * 60 * 1000;
const STATUSES = ['new', 'work', 'done'];
const IP_RE = /^[0-9a-fA-F:.]{3,64}$/;
const DAY = 24 * 60 * 60 * 1000;
const SESSION_MS = 30 * DAY;
const MAX_BODY = 2_000_000;           // байт; фото ≤ ~1,4 МБ в base64
// Лимиты чата: окно 10 минут. IP может быть общим (мобильный интернет, Wi-Fi), поэтому для него лимит щедрее.
const RATE_WINDOW = 10 * 60 * 1000;
const RATE_DEVICE_MSGS = 20;
const RATE_DEVICE_IMAGES = 5;
const RATE_IP_MSGS = 60;
const RATE_IP_NEW_THREADS = 5;        // новых чатов с одного IP за час

async function sha256(s) {
  const b = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(s));
  return [...new Uint8Array(b)].map((x) => x.toString(16).padStart(2, '0')).join('');
}

async function ensureSchema(db) {
  if (ready) return;
  await db.batch(SCHEMA.map((q) => db.prepare(q)));
  for (const q of MIGRATIONS) {
    try { await db.prepare(q).run(); } catch (e) { /* колонка уже есть */ }
  }
  ready = true;
}

// Проверка доступа админа. Приложение входит по коду один раз (/api/admin/login), получает токен сессии
// и дальше шлёт только его (X-Admin-Token). Старые версии приложения шлют код в X-Admin-Code — это пока работает
// (чтобы отключить, задайте переменную LEGACY_ADMIN_CODE = off).
// После 5 неверных попыток с одного адреса — блокировка на 15 минут.
async function adminAuth(request, env, path) {
  const ip = request.headers.get('cf-connecting-ip') || 'unknown';
  const now = Date.now();
  const row = await env.DB.prepare('SELECT n, ts FROM fails WHERE ip = ?').bind(ip).first();
  if (row && row.n >= 5 && now - row.ts < LOCK_MS) return 'locked';

  const fp = env.ADMIN_CODE ? await sha256('fp:' + env.ADMIN_CODE) : '';
  const code = request.headers.get('x-admin-code') || '';
  const token = request.headers.get('x-admin-token') || '';
  let ok = false;
  if (path !== '/api/admin/login' && token.length >= 32 && fp) {
    const s = await env.DB.prepare('SELECT code_fp, exp FROM sessions WHERE hash = ?').bind(await sha256(token)).first();
    ok = !!s && s.exp > now && s.code_fp === fp;
  } else if (code.length > 0 && !!env.ADMIN_CODE && (path === '/api/admin/login' || env.LEGACY_ADMIN_CODE !== 'off')) {
    ok = (await sha256(code)) === (await sha256(env.ADMIN_CODE));
  }
  if (!ok) {
    const n = row && now - row.ts < LOCK_MS ? row.n + 1 : 1;
    await env.DB.prepare(
      'INSERT INTO fails (ip, n, ts) VALUES (?, ?, ?) ON CONFLICT(ip) DO UPDATE SET n = excluded.n, ts = excluded.ts'
    ).bind(ip, n, now).run();
    return false;
  }
  if (row) await env.DB.prepare('DELETE FROM fails WHERE ip = ?').bind(ip).run();
  return true;
}

// Вход по коду: выдаёт токен сессии на 30 дней. Токен — 32 случайных байта, в базе только его SHA-256.
async function adminLogin(env) {
  const bytes = crypto.getRandomValues(new Uint8Array(32));
  const token = [...bytes].map((x) => x.toString(16).padStart(2, '0')).join('');
  const now = Date.now();
  await env.DB.batch([
    env.DB.prepare('DELETE FROM sessions WHERE exp < ?').bind(now),
    env.DB.prepare('INSERT INTO sessions (hash, code_fp, created, exp) VALUES (?, ?, ?, ?)')
      .bind(await sha256(token), await sha256('fp:' + env.ADMIN_CODE), now, now + SESSION_MS),
  ]);
  return json({ token, expires: now + SESSION_MS });
}

async function adminLogout(request, env) {
  const token = request.headers.get('x-admin-token') || '';
  if (token) await env.DB.prepare('DELETE FROM sessions WHERE hash = ?').bind(await sha256(token)).run();
  return json({ ok: true });
}

// Лимиты на отправку в чат. Возвращает текст ошибки или '' (можно писать).
async function chatRateLimit(env, device, ip, hasImage, isNewThread) {
  const since = Date.now() - RATE_WINDOW;
  if (isNewThread && ip && ip !== 'unknown') {
    const r = await env.DB.prepare('SELECT COUNT(*) AS n FROM threads WHERE last_ip = ? AND created > ?')
      .bind(ip, Date.now() - 60 * 60 * 1000).first();
    if (r && r.n >= RATE_IP_NEW_THREADS) return 'Слишком много новых обращений с вашего адреса. Попробуйте позже';
  }
  const d = await env.DB.prepare(
    `SELECT COUNT(*) AS n, COALESCE(SUM(image != ''), 0) AS imgs FROM messages WHERE device = ? AND sender = 'user' AND ts > ?`
  ).bind(device, since).first();
  if (d && d.n >= RATE_DEVICE_MSGS) return 'Слишком много сообщений. Подождите несколько минут';
  if (hasImage && d && d.imgs >= RATE_DEVICE_IMAGES) return 'Слишком много фото. Подождите несколько минут';
  if (ip && ip !== 'unknown') {
    const i = await env.DB.prepare(`SELECT COUNT(*) AS n FROM messages WHERE ip = ? AND sender = 'user' AND ts > ?`)
      .bind(ip, since).first();
    if (i && i.n >= RATE_IP_MSGS) return 'Слишком много сообщений с вашего адреса. Подождите несколько минут';
  }
  return '';
}

// Пользователь видит только сообщения; админ — ещё IP, устройство и страну отправителя.
const MSG_SQL = `SELECT id, sender AS "from", text, ts, (image != '') AS has_image FROM messages WHERE device = ? AND id > ? ORDER BY id LIMIT 200`;
const MSG_SQL_ADMIN = `SELECT id, sender AS "from", text, ts, (image != '') AS has_image, ip, dev, geo FROM messages WHERE device = ? AND id > ? ORDER BY id LIMIT 200`;

function cleanImage(v) {
  const img = String(v || '');
  if (!img) return '';
  if (img.length > MAX_IMAGE || !B64.test(img)) return null;
  return img;
}

async function telegramNote(env, note, image) {
  const base = `https://api.telegram.org/bot${env.BOT_TOKEN}`;
  if (image) {
    const bin = Uint8Array.from(atob(image), (c) => c.charCodeAt(0));
    const fd = new FormData();
    fd.append('chat_id', env.CHAT_ID);
    fd.append('caption', note.slice(0, 1000));
    fd.append('photo', new Blob([bin], { type: 'image/jpeg' }), 'photo.jpg');
    return fetch(`${base}/sendPhoto`, { method: 'POST', body: fd });
  }
  return fetch(`${base}/sendMessage`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ chat_id: env.CHAT_ID, text: note.slice(0, 4000) }),
  });
}

// ---------- клиент ----------
async function userSend(body, env, ctx, request) {
  const device = String(body.device || '');
  const secret = String(body.secret || '');
  const text = String(body.text || '').trim().slice(0, 1000);
  const image = cleanImage(body.image);
  if (!UUID.test(device) || secret.length < 16) return err('Некорректный запрос');
  if (image === null) return err('Фото слишком большое или повреждено');
  if (!text && !image) return err('Пустое сообщение');
  const hash = await sha256(secret);
  const blockedBy = await isBlocked(env, device, (request.headers.get('cf-connecting-ip') || '').slice(0, 64));
  if (blockedBy) return err('Доступ к поддержке ограничен', 403);
  const nick = String(body.nick || '').replace(/^@/, '').trim().slice(0, 40);
  const order = String(body.order || '').trim().slice(0, 40);
  const now = Date.now();

  // что известно об отправителе: IP и страна/город — от Cloudflare, модель и версии — от приложения
  const ip = (request.headers.get('cf-connecting-ip') || 'unknown').slice(0, 64);
  const cf = request.cf || {};
  const geo = [cf.country, cf.city].filter(Boolean).join(', ').slice(0, 80);
  const dev = String(body.info || '').replace(/[\r\n]+/g, ' ').trim().slice(0, 300) ||
    (request.headers.get('user-agent') || '').slice(0, 300);
  const shown = text || '📷 Фото';

  let t = await env.DB.prepare('SELECT secret_hash, last_user_ts FROM threads WHERE device = ?').bind(device).first();
  // чужому устройству отказываем до подсчёта лимитов; новый чат и частота сообщений проверяются здесь
  if (t && t.secret_hash !== hash) return err('Нет доступа', 403);
  const limited = await chatRateLimit(env, device, ip, !!image, !t);
  if (limited) return err(limited, 429);
  if (!t) {
    await env.DB.prepare(
      `INSERT INTO threads (device, secret_hash, nick, last_order, last_text, last_ts, last_user_ts, unread, created)
       VALUES (?, ?, ?, ?, '', ?, 0, 0, ?)`
    ).bind(device, hash, nick, order, now, now).run();
    t = { secret_hash: hash, last_user_ts: 0 };
  }
  if (t.secret_hash !== hash) return err('Нет доступа', 403);
  if (now - (t.last_user_ts || 0) < 1500) return err('Слишком часто, подождите секунду', 429);

  await env.DB.batch([
    env.DB.prepare(
      `INSERT INTO messages (device, sender, text, ts, image, ip, dev, geo) VALUES (?, 'user', ?, ?, ?, ?, ?, ?)`
    ).bind(device, text, now, image, ip, dev, geo),
    env.DB.prepare(
      `UPDATE threads SET nick = CASE WHEN ? != '' THEN ? ELSE nick END,
         last_order = CASE WHEN ? != '' THEN ? ELSE last_order END,
         last_text = ?, last_ts = ?, last_user_ts = ?, unread = unread + 1, last_ip = ?, last_dev = ?,
         status = CASE WHEN status = 'done' THEN 'new' ELSE status END WHERE device = ?`
    ).bind(nick, nick, order, order, shown, now, now, ip, dev, device),
  ]);

  // уведомление в Telegram (по желанию)
  if (env.BOT_TOKEN && env.CHAT_ID) {
    const note =
      `💬 Новое сообщение в поддержку\nОт: ${nick ? '@' + nick : 'гость'}` +
      `${order ? '\nЗаказ: ' + order : ''}\nIP: ${ip}${geo ? ' (' + geo + ')' : ''}\nУстройство: ${dev || '—'}` +
      `\n\n${text || '📷 Фото'}\n\nОтветьте в приложении (админ-панель).`;
    ctx.waitUntil(telegramNote(env, note, image).catch(() => {}));
  }
  return json({ ok: true });
}

async function userMessages(body, env) {
  const device = String(body.device || '');
  const secret = String(body.secret || '');
  if (!UUID.test(device) || secret.length < 16) return err('Некорректный запрос');
  const t = await env.DB.prepare('SELECT secret_hash, admin_read_ts FROM threads WHERE device = ?').bind(device).first();
  if (!t) return json({ messages: [], read_ts: 0 });
  if (t.secret_hash !== (await sha256(secret))) return err('Нет доступа', 403);
  const { results } = await env.DB.prepare(MSG_SQL).bind(device, Number(body.after) || 0).all();
  // пользователь открыл чат — ответы админа прочитаны (пишем в базу, только если есть что отметить)
  await env.DB.prepare(
    `UPDATE threads SET user_read_ts = ? WHERE device = ?
       AND user_read_ts < (SELECT COALESCE(MAX(ts), 0) FROM messages WHERE device = ? AND sender = 'admin')`
  ).bind(Date.now(), device, device).run();
  return json({ messages: results, read_ts: t.admin_read_ts || 0 });
}

// Лёгкий опрос для фоновых уведомлений: сколько ответов админа пользователь ещё не прочитал.
// Ничего не помечает прочитанным (в отличие от /api/chat/messages).
async function userPoll(body, env) {
  const device = String(body.device || '');
  const secret = String(body.secret || '');
  if (!UUID.test(device) || secret.length < 16) return err('Некорректный запрос');
  const t = await env.DB.prepare('SELECT secret_hash, user_read_ts FROM threads WHERE device = ?').bind(device).first();
  if (!t) return json({ unread: 0, last_id: 0, text: '' });
  if (t.secret_hash !== (await sha256(secret))) return err('Нет доступа', 403);
  const n = await env.DB.prepare(
    `SELECT COUNT(*) AS n, COALESCE(MAX(id), 0) AS last_id FROM messages WHERE device = ? AND sender = 'admin' AND ts > ?`
  ).bind(device, t.user_read_ts || 0).first();
  let text = '';
  if (n && n.n > 0) {
    const m = await env.DB.prepare(`SELECT text, (image != '') AS has_image FROM messages WHERE id = ?`).bind(n.last_id).first();
    text = m ? (m.text || (m.has_image ? '📷 Фото' : '')).slice(0, 160) : '';
  }
  return json({ unread: n ? n.n : 0, last_id: n ? n.last_id : 0, text });
}

async function userImage(body, env) {
  const device = String(body.device || '');
  const secret = String(body.secret || '');
  if (!UUID.test(device) || secret.length < 16) return err('Некорректный запрос');
  const t = await env.DB.prepare('SELECT secret_hash FROM threads WHERE device = ?').bind(device).first();
  if (!t || t.secret_hash !== (await sha256(secret))) return err('Нет доступа', 403);
  const m = await env.DB.prepare('SELECT image FROM messages WHERE id = ? AND device = ?').bind(Number(body.id) || 0, device).first();
  if (!m || !m.image) return err('Фото не найдено', 404);
  return json({ image: m.image });
}

// ---------- админ ----------
// LIKE-шаблон без спецсимволов пользователя
const likeOf = (q) => '%' + q.replace(/[\\%_]/g, (m) => '\\' + m) + '%';

async function isBlocked(env, device, ip) {
  const r = await env.DB.prepare(
    `SELECT kind FROM blocks WHERE (kind = 'device' AND value = ?) OR (kind = 'ip' AND value = ? AND ? != '' AND ? != 'unknown') LIMIT 1`
  ).bind(device, ip, ip, ip).first();
  return r ? r.kind : '';
}

async function adminThreads(body, env) {
  const where = [`last_text != ''`];
  const args = [];
  const st = String(body.status || '');
  if (STATUSES.includes(st)) { where.push('status = ?'); args.push(st); }
  if (body.unread) where.push('unread > 0');
  const q = String(body.q || '').trim().replace(/^@/, '').slice(0, 60);
  if (q) {
    // по нику, по номеру заказа (в карточке и в тексте сообщений)
    where.push(`(nick LIKE ? ESCAPE '\\' OR last_order LIKE ? ESCAPE '\\' OR device IN (SELECT device FROM messages WHERE text LIKE ? ESCAPE '\\'))`);
    const like = likeOf(q);
    args.push(like, like, like);
  }
  const { results } = await env.DB.prepare(
    `SELECT device, nick, last_order, last_text, last_ts, unread, last_ip, last_dev, status
       FROM threads WHERE ${where.join(' AND ')} ORDER BY last_ts DESC LIMIT 100`
  ).bind(...args).all();

  // подозрительные совпадения: сколько разных устройств писали с того же IP за 30 дней
  const ips = [...new Set(results.map((r) => r.last_ip).filter((x) => x && x !== 'unknown'))];
  const shared = {};
  for (let i = 0; i < ips.length; i += 80) {       // у D1 лимит ~100 параметров на запрос
    const part = ips.slice(i, i + 80);
    const { results: rows } = await env.DB.prepare(
      `SELECT ip, COUNT(DISTINCT device) AS n FROM messages WHERE ip IN (${part.map(() => '?').join(',')}) AND ts > ? GROUP BY ip`
    ).bind(...part, Date.now() - 30 * DAY).all();
    for (const r of rows) shared[r.ip] = r.n;
  }
  const { results: bl } = await env.DB.prepare('SELECT kind, value FROM blocks').all();
  const blDev = new Set(bl.filter((b) => b.kind === 'device').map((b) => b.value));
  const blIp = new Set(bl.filter((b) => b.kind === 'ip').map((b) => b.value));
  const threads = results.map((r) => ({
    ...r,
    status: STATUSES.includes(r.status) ? r.status : 'new',
    shared_ip: (shared[r.last_ip] || 0) > 1 ? shared[r.last_ip] : 0,
    blocked_device: blDev.has(r.device),
    blocked_ip: blIp.has(r.last_ip),
  }));

  const counts = { new: 0, work: 0, done: 0, unread: 0 };
  const { results: c1 } = await env.DB.prepare(`SELECT status, COUNT(*) AS n FROM threads WHERE last_text != '' GROUP BY status`).all();
  for (const r of c1) counts[STATUSES.includes(r.status) ? r.status : 'new'] += r.n;
  const c2 = await env.DB.prepare(`SELECT COUNT(*) AS n FROM threads WHERE last_text != '' AND unread > 0`).first();
  counts.unread = c2 ? c2.n : 0;
  return json({ threads, counts });
}

// Другие устройства, писавшие с тех же IP, что и этот чат (за 30 дней)
async function adminRelated(body, env) {
  const device = String(body.device || '');
  if (!UUID.test(device)) return err('Некорректный запрос');
  const since = Date.now() - 30 * DAY;
  const { results: mine } = await env.DB.prepare(
    `SELECT DISTINCT ip FROM messages WHERE device = ? AND ip != '' AND ip != 'unknown' AND ts > ? LIMIT 20`
  ).bind(device, since).all();
  if (!mine.length) return json({ related: [] });
  const ips = mine.map((r) => r.ip);
  const { results } = await env.DB.prepare(
    `SELECT m.device AS device, t.nick AS nick, m.ip AS ip, MAX(m.ts) AS ts
       FROM messages m JOIN threads t ON t.device = m.device
      WHERE m.ip IN (${ips.map(() => '?').join(',')}) AND m.device != ? AND m.ts > ?
      GROUP BY m.device, m.ip ORDER BY ts DESC LIMIT 20`
  ).bind(...ips, device, since).all();
  return json({ related: results });
}

// Для уведомлений админу: сколько чатов с непрочитанными сообщениями и какой писал последним.
async function adminPoll(env) {
  const c = await env.DB.prepare('SELECT COUNT(*) AS n FROM threads WHERE unread > 0').first();
  const last = await env.DB.prepare(
    'SELECT nick, last_text, last_ts FROM threads WHERE unread > 0 ORDER BY last_ts DESC LIMIT 1'
  ).first();
  return json({
    unread: c ? c.n : 0,
    last_ts: last ? last.last_ts : 0,
    nick: last ? last.nick || '' : '',
    text: last ? String(last.last_text || '').slice(0, 160) : '',
  });
}

async function adminSetStatus(body, env) {
  const device = String(body.device || '');
  const status = String(body.status || '');
  if (!UUID.test(device) || !STATUSES.includes(status)) return err('Некорректный запрос');
  await env.DB.prepare('UPDATE threads SET status = ? WHERE device = ?').bind(status, device).run();
  return json({ ok: true });
}

// ---------- блокировки ----------
function blockTarget(body) {
  const kind = String(body.kind || '');
  const value = String(body.value || '').trim();
  if (kind === 'device' && UUID.test(value)) return { kind, value };
  if (kind === 'ip' && IP_RE.test(value) && value !== 'unknown') return { kind, value };
  return null;
}
async function adminBlock(body, env) {
  const b = blockTarget(body);
  if (!b) return err('Некорректный запрос');
  const reason = String(body.reason || '').trim().slice(0, 100);
  await env.DB.prepare(
    'INSERT INTO blocks (kind, value, reason, ts) VALUES (?, ?, ?, ?) ON CONFLICT(kind, value) DO UPDATE SET reason = excluded.reason, ts = excluded.ts'
  ).bind(b.kind, b.value, reason, Date.now()).run();
  return json({ ok: true });
}
async function adminUnblock(body, env) {
  const b = blockTarget(body);
  if (!b) return err('Некорректный запрос');
  await env.DB.prepare('DELETE FROM blocks WHERE kind = ? AND value = ?').bind(b.kind, b.value).run();
  return json({ ok: true });
}
async function adminBlocks(env) {
  const { results } = await env.DB.prepare(
    `SELECT b.kind AS kind, b.value AS value, b.reason AS reason, b.ts AS ts,
            (SELECT nick FROM threads t WHERE (b.kind = 'device' AND t.device = b.value) OR (b.kind = 'ip' AND t.last_ip = b.value) LIMIT 1) AS nick
       FROM blocks b ORDER BY b.ts DESC LIMIT 200`
  ).all();
  return json({ blocks: results });
}

// ---------- удалённые настройки: объявление, разделы, обязательное обновление ----------
const SECTION_KEYS = ['stars', 'premium', 'gifts', 'nft_buy', 'nft_rent', 'stars_sell', 'nft_sell'];
const DEFAULT_APP = {
  announcement: { active: false, text: '', level: 'info', id: 0 },
  sections: {},
  min_version_code: 0,
  update_message: '',
};

async function loadApp(env) {
  const r = await env.DB.prepare(`SELECT v FROM settings WHERE k = 'app'`).first();
  if (!r) return structuredClone(DEFAULT_APP);
  try { return { ...structuredClone(DEFAULT_APP), ...JSON.parse(r.v) }; } catch (e) { return structuredClone(DEFAULT_APP); }
}

const cut = (v, n) => String(v == null ? '' : v).trim().slice(0, n);

function applyPatch(cur, patch) {
  const out = { ...cur };
  if (patch.announcement && typeof patch.announcement === 'object') {
    const a = patch.announcement;
    const text = cut(a.text, 300);
    out.announcement = { active: !!a.active && text.length > 0, text, level: a.level === 'warn' ? 'warn' : 'info', id: Date.now() };
  }
  if (patch.sections && typeof patch.sections === 'object') {
    const secs = {};
    for (const key of SECTION_KEYS) {
      const v = patch.sections[key];
      if (!v || typeof v !== 'object') continue;
      const s = {};
      if (typeof v.enabled === 'boolean') s.enabled = v.enabled;
      for (const [f, n] of [['title', 40], ['sub', 60], ['message', 100]]) { const t = cut(v[f], n); if (t) s[f] = t; }
      const url = cut(v.url, 200);
      if (/^https:\/\//.test(url)) s.url = url;   // только https-ссылки
      secs[key] = s;
    }
    out.sections = secs;
  }
  if (patch.min_version_code !== undefined) {
    const n = Math.floor(Number(patch.min_version_code));
    out.min_version_code = Number.isFinite(n) ? Math.min(Math.max(n, 0), 1_000_000) : 0;
  }
  if (patch.update_message !== undefined) out.update_message = cut(patch.update_message, 200);
  return out;
}

async function adminSettings(env) { return json(await loadApp(env)); }
async function adminSaveSettings(body, env) {
  const next = applyPatch(await loadApp(env), body || {});
  await env.DB.prepare(`INSERT INTO settings (k, v) VALUES ('app', ?) ON CONFLICT(k) DO UPDATE SET v = excluded.v`)
    .bind(JSON.stringify(next)).run();
  return json(next);
}

// публично: приложение спрашивает при запуске и при открытии главного экрана
async function appConfig(env) {
  const a = await loadApp(env);
  return json({
    announcement: a.announcement.active ? { active: true, text: a.announcement.text, level: a.announcement.level, id: a.announcement.id } : { active: false },
    sections: a.sections,
    min_version_code: a.min_version_code,
    update_message: a.update_message,
  });
}

async function adminMessages(body, env) {
  const device = String(body.device || '');
  if (!UUID.test(device)) return err('Некорректный запрос');
  const { results } = await env.DB.prepare(MSG_SQL_ADMIN).bind(device, Number(body.after) || 0).all();
  const t = await env.DB.prepare('SELECT user_read_ts FROM threads WHERE device = ?').bind(device).first();
  // админ открыл чат — сообщения пользователя прочитаны (пишем, только если есть новое)
  await env.DB.prepare(
    'UPDATE threads SET unread = 0, admin_read_ts = ? WHERE device = ? AND (unread > 0 OR admin_read_ts < last_user_ts)'
  ).bind(Date.now(), device).run();
  return json({ messages: results, read_ts: t ? t.user_read_ts || 0 : 0 });
}

async function adminImage(body, env) {
  const device = String(body.device || '');
  if (!UUID.test(device)) return err('Некорректный запрос');
  const m = await env.DB.prepare('SELECT image FROM messages WHERE id = ? AND device = ?').bind(Number(body.id) || 0, device).first();
  if (!m || !m.image) return err('Фото не найдено', 404);
  return json({ image: m.image });
}

async function adminReply(body, env) {
  const device = String(body.device || '');
  const text = String(body.text || '').trim().slice(0, 1000);
  const image = cleanImage(body.image);
  if (!UUID.test(device)) return err('Некорректный запрос');
  if (image === null) return err('Фото слишком большое или повреждено');
  if (!text && !image) return err('Пустое сообщение');
  const t = await env.DB.prepare('SELECT device FROM threads WHERE device = ?').bind(device).first();
  if (!t) return err('Чат не найден', 404);
  const now = Date.now();
  await env.DB.batch([
    env.DB.prepare(`INSERT INTO messages (device, sender, text, ts, image) VALUES (?, 'admin', ?, ?, ?)`).bind(device, text, now, image),
    env.DB.prepare(
      `UPDATE threads SET last_text = ?, last_ts = ?, unread = 0, admin_read_ts = ?,
         status = CASE WHEN status = 'new' THEN 'work' ELSE status END WHERE device = ?`
    ).bind(text || '📷 Фото', now, now, device),
  ]);
  return json({ ok: true });
}

export default {
  async fetch(request, env, ctx) {
    if (request.method === 'GET') return new Response('PharmShop chat OK');
    if (request.method !== 'POST') return err('Not found', 404);
    if (!env.DB) return err('База данных не подключена (переменная DB)', 500);
    if (Number(request.headers.get('content-length') || 0) > MAX_BODY) return err('Слишком большой запрос', 413);
    await ensureSchema(env.DB);
    const path = new URL(request.url).pathname;
    let body = {};
    try { body = await request.json(); } catch (e) { /* пустое тело */ }

    if (path === '/api/chat/send') return userSend(body, env, ctx, request);
    if (path === '/api/chat/messages') return userMessages(body, env);
    if (path === '/api/chat/image') return userImage(body, env);
    if (path === '/api/chat/poll') return userPoll(body, env);
    if (path === '/api/app/config') return appConfig(env);

    if (path.startsWith('/api/admin/')) {
      const a = await adminAuth(request, env, path);
      if (a === 'locked') return err('Слишком много попыток. Попробуйте через 15 минут', 429);
      if (!a) return err('Неверный код', 401);
      if (path === '/api/admin/login') return adminLogin(env);
      if (path === '/api/admin/logout') return adminLogout(request, env);
      if (path === '/api/admin/threads') return adminThreads(body, env);
      if (path === '/api/admin/poll') return adminPoll(env);
      if (path === '/api/admin/related') return adminRelated(body, env);
      if (path === '/api/admin/status') return adminSetStatus(body, env);
      if (path === '/api/admin/block') return adminBlock(body, env);
      if (path === '/api/admin/unblock') return adminUnblock(body, env);
      if (path === '/api/admin/blocks') return adminBlocks(env);
      if (path === '/api/admin/settings') return adminSettings(env);
      if (path === '/api/admin/settings_save') return adminSaveSettings(body, env);
      if (path === '/api/admin/messages') return adminMessages(body, env);
      if (path === '/api/admin/reply') return adminReply(body, env);
      if (path === '/api/admin/image') return adminImage(body, env);
    }
    return err('Not found', 404);
  },
};
