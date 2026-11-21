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
];
let ready = false;

const json = (o, s = 200) =>
  new Response(JSON.stringify(o), {
    status: s,
    headers: { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' },
  });
const err = (m, s = 400) => json({ error: m }, s);
const UUID = /^[0-9a-f-]{36}$/;
const LOCK_MS = 15 * 60 * 1000;

async function sha256(s) {
  const b = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(s));
  return [...new Uint8Array(b)].map((x) => x.toString(16).padStart(2, '0')).join('');
}

async function ensureSchema(db) {
  if (ready) return;
  await db.batch(SCHEMA.map((q) => db.prepare(q)));
  ready = true;
}

// Проверка кода админа: после 5 неверных попыток с одного адреса — блокировка на 15 минут.
async function adminAuth(request, env) {
  const ip = request.headers.get('cf-connecting-ip') || 'unknown';
  const now = Date.now();
  const row = await env.DB.prepare('SELECT n, ts FROM fails WHERE ip = ?').bind(ip).first();
  if (row && row.n >= 5 && now - row.ts < LOCK_MS) return 'locked';
  const given = request.headers.get('x-admin-code') || '';
  const ok = given.length > 0 && !!env.ADMIN_CODE && (await sha256(given)) === (await sha256(env.ADMIN_CODE));
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

const MSG_SQL = `SELECT id, sender AS "from", text, ts FROM messages WHERE device = ? AND id > ? ORDER BY id LIMIT 200`;

// ---------- клиент ----------
async function userSend(body, env, ctx) {
  const device = String(body.device || '');
  const secret = String(body.secret || '');
  const text = String(body.text || '').trim().slice(0, 1000);
  if (!UUID.test(device) || secret.length < 16) return err('Некорректный запрос');
  if (!text) return err('Пустое сообщение');
  const hash = await sha256(secret);
  const nick = String(body.nick || '').replace(/^@/, '').trim().slice(0, 40);
  const order = String(body.order || '').trim().slice(0, 40);
  const now = Date.now();

  let t = await env.DB.prepare('SELECT secret_hash, last_user_ts FROM threads WHERE device = ?').bind(device).first();
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
    env.DB.prepare(`INSERT INTO messages (device, sender, text, ts) VALUES (?, 'user', ?, ?)`).bind(device, text, now),
    env.DB.prepare(
      `UPDATE threads SET nick = CASE WHEN ? != '' THEN ? ELSE nick END,
         last_order = CASE WHEN ? != '' THEN ? ELSE last_order END,
         last_text = ?, last_ts = ?, last_user_ts = ?, unread = unread + 1 WHERE device = ?`
    ).bind(nick, nick, order, order, text, now, now, device),
  ]);

  // уведомление в Telegram (по желанию)
  if (env.BOT_TOKEN && env.CHAT_ID) {
    const note =
      `💬 Новое сообщение в поддержку\nОт: ${nick ? '@' + nick : 'гость'}` +
      `${order ? '\nЗаказ: ' + order : ''}\n\n${text}\n\nОтветьте в приложении (админ-панель).`;
    ctx.waitUntil(
      fetch(`https://api.telegram.org/bot${env.BOT_TOKEN}/sendMessage`, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ chat_id: env.CHAT_ID, text: note.slice(0, 4000) }),
      }).catch(() => {})
    );
  }
  return json({ ok: true });
}

async function userMessages(body, env) {
  const device = String(body.device || '');
  const secret = String(body.secret || '');
  if (!UUID.test(device) || secret.length < 16) return err('Некорректный запрос');
  const t = await env.DB.prepare('SELECT secret_hash FROM threads WHERE device = ?').bind(device).first();
  if (!t) return json({ messages: [] });
  if (t.secret_hash !== (await sha256(secret))) return err('Нет доступа', 403);
  const { results } = await env.DB.prepare(MSG_SQL).bind(device, Number(body.after) || 0).all();
  return json({ messages: results });
}

// ---------- админ ----------
async function adminThreads(env) {
  const { results } = await env.DB.prepare(
    'SELECT device, nick, last_order, last_text, last_ts, unread FROM threads WHERE last_text != \'\' ORDER BY last_ts DESC LIMIT 100'
  ).all();
  return json({ threads: results });
}

async function adminMessages(body, env) {
  const device = String(body.device || '');
  if (!UUID.test(device)) return err('Некорректный запрос');
  const { results } = await env.DB.prepare(MSG_SQL).bind(device, Number(body.after) || 0).all();
  await env.DB.prepare('UPDATE threads SET unread = 0 WHERE device = ? AND unread > 0').bind(device).run();
  return json({ messages: results });
}

async function adminReply(body, env) {
  const device = String(body.device || '');
  const text = String(body.text || '').trim().slice(0, 1000);
  if (!UUID.test(device)) return err('Некорректный запрос');
  if (!text) return err('Пустое сообщение');
  const t = await env.DB.prepare('SELECT device FROM threads WHERE device = ?').bind(device).first();
  if (!t) return err('Чат не найден', 404);
  const now = Date.now();
  await env.DB.batch([
    env.DB.prepare(`INSERT INTO messages (device, sender, text, ts) VALUES (?, 'admin', ?, ?)`).bind(device, text, now),
    env.DB.prepare('UPDATE threads SET last_text = ?, last_ts = ?, unread = 0 WHERE device = ?').bind(text, now, device),
  ]);
  return json({ ok: true });
}

export default {
  async fetch(request, env, ctx) {
    if (request.method === 'GET') return new Response('PharmShop chat OK');
    if (request.method !== 'POST') return err('Not found', 404);
    if (!env.DB) return err('База данных не подключена (переменная DB)', 500);
    await ensureSchema(env.DB);
    const path = new URL(request.url).pathname;
    let body = {};
    try { body = await request.json(); } catch (e) { /* пустое тело */ }

    if (path === '/api/chat/send') return userSend(body, env, ctx);
    if (path === '/api/chat/messages') return userMessages(body, env);

    if (path.startsWith('/api/admin/')) {
      const a = await adminAuth(request, env);
      if (a === 'locked') return err('Слишком много попыток. Попробуйте через 15 минут', 429);
      if (!a) return err('Неверный код', 401);
      if (path === '/api/admin/threads') return adminThreads(env);
      if (path === '/api/admin/messages') return adminMessages(body, env);
      if (path === '/api/admin/reply') return adminReply(body, env);
    }
    return err('Not found', 404);
  },
};
