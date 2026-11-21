"""
Мост между приложением PharmShop и вашим Telegram-ботом.

Как работает вход:
  1. Приложение: POST /app/auth/start  -> {token, bot_url}
  2. Приложение открывает https://t.me/<бот>?start=login_<token>
  3. Пользователь жмёт «Старт» -> бот получает "/start login_<token>"
     и вызывает confirm_login(token, tg_user)  (см. register_bot_handlers)
  4. Приложение каждые пару секунд: GET /app/auth/poll?token=...
     и получает session + данные пользователя, как только бот подтвердил.
  5. Дальше приложение ходит с заголовком Authorization: Bearer <session>.

Запуск отдельно:   BOT_TOKEN=123:ABC python app_auth.py
Встроить в своего бота: см. register_bot_handlers() и make_app() внизу.

Зависимости: pip install aiohttp aiogram>=3.4
"""
import asyncio
import hashlib
import os
import secrets
import sqlite3
import time

from aiohttp import web

BOT_USERNAME = os.getenv("BOT_USERNAME", "pharmaishop_bot")
DB_PATH = os.getenv("APP_AUTH_DB", "app_auth.sqlite3")
LOGIN_TTL = 5 * 60              # сколько живёт токен входа, сек
SESSION_TTL = 90 * 24 * 3600    # сколько живёт сессия, сек


# ------------------------------------------------------------------ БД
def db() -> sqlite3.Connection:
    c = sqlite3.connect(DB_PATH)
    c.row_factory = sqlite3.Row
    return c


def init_db() -> None:
    with db() as c:
        c.executescript(
            """
            CREATE TABLE IF NOT EXISTS login_tokens(
                token TEXT PRIMARY KEY,
                created_at INTEGER NOT NULL,
                tg_id INTEGER, username TEXT, first_name TEXT,
                consumed INTEGER NOT NULL DEFAULT 0
            );
            CREATE TABLE IF NOT EXISTS sessions(
                token_hash TEXT PRIMARY KEY,
                tg_id INTEGER NOT NULL, username TEXT, first_name TEXT,
                created_at INTEGER NOT NULL
            );
            -- заказы, привязанные к пользователю (из приложения)
            CREATE TABLE IF NOT EXISTS app_orders(
                order_id TEXT PRIMARY KEY,
                tg_id INTEGER NOT NULL,
                data TEXT
            );
            """
        )


def _hash(s: str) -> str:
    return hashlib.sha256(s.encode()).hexdigest()


# ------------------------------------------------------------------ логика входа
def confirm_login(token: str, tg_id: int, username: str | None, first_name: str | None) -> bool:
    """Вызывается ботом по /start login_<token>. True — токен принят."""
    with db() as c:
        row = c.execute("SELECT * FROM login_tokens WHERE token=?", (token,)).fetchone()
        if not row or row["tg_id"] is not None or time.time() - row["created_at"] > LOGIN_TTL:
            return False
        c.execute(
            "UPDATE login_tokens SET tg_id=?, username=?, first_name=? WHERE token=?",
            (tg_id, username or "", first_name or "", token),
        )
    return True


# ------------------------------------------------------------------ ЗАКАЗЫ: подключите свою БД здесь
def fetch_orders(tg_id: int) -> list[dict]:
    """
    Верните заказы пользователя с этим tg_id в формате:
      {id, order_number, status, quantity, username, total, currency,
       created_at (ISO 8601), payment_url, method_name}

    !!! Здесь нужно подключить таблицу заказов вашего бота. !!!
    Пока отдаём заказы, которые приложение привязало через /app/orders/link.
    """
    import json
    with db() as c:
        rows = c.execute("SELECT data FROM app_orders WHERE tg_id=?", (tg_id,)).fetchall()
    out = []
    for r in rows:
        try:
            out.append(json.loads(r["data"]))
        except Exception:
            pass
    return out


# ------------------------------------------------------------------ HTTP
routes = web.RouteTableDef()


def _session_user(request: web.Request):
    auth = request.headers.get("Authorization", "")
    if not auth.startswith("Bearer "):
        return None
    h = _hash(auth[7:])
    with db() as c:
        row = c.execute("SELECT * FROM sessions WHERE token_hash=?", (h,)).fetchone()
    if not row or time.time() - row["created_at"] > SESSION_TTL:
        return None
    return row


def _unauth():
    return web.json_response({"error": "unauthorized"}, status=401)


@routes.post("/app/auth/start")
async def auth_start(request: web.Request):
    token = secrets.token_urlsafe(24)  # 32 символа, подходит для start-параметра Telegram
    with db() as c:
        c.execute("DELETE FROM login_tokens WHERE created_at < ?", (time.time() - 3600,))
        c.execute("INSERT INTO login_tokens(token, created_at) VALUES(?,?)", (token, int(time.time())))
    return web.json_response({
        "token": token,
        "bot_url": f"https://t.me/{BOT_USERNAME}?start=login_{token}",
        "expires_in": LOGIN_TTL,
    })


@routes.get("/app/auth/poll")
async def auth_poll(request: web.Request):
    token = request.query.get("token", "")
    with db() as c:
        row = c.execute("SELECT * FROM login_tokens WHERE token=?", (token,)).fetchone()
        if not row or row["consumed"] or time.time() - row["created_at"] > LOGIN_TTL:
            return web.json_response({"status": "expired"})
        if row["tg_id"] is None:
            return web.json_response({"status": "pending"})
        # выдаём сессию один раз
        session = secrets.token_urlsafe(32)
        c.execute("UPDATE login_tokens SET consumed=1 WHERE token=?", (token,))
        c.execute(
            "INSERT INTO sessions(token_hash, tg_id, username, first_name, created_at) VALUES(?,?,?,?,?)",
            (_hash(session), row["tg_id"], row["username"], row["first_name"], int(time.time())),
        )
    return web.json_response({
        "status": "ok",
        "session": session,
        "user": {"id": row["tg_id"], "username": row["username"], "first_name": row["first_name"]},
    })


@routes.get("/app/me")
async def me(request: web.Request):
    u = _session_user(request)
    if not u:
        return _unauth()
    return web.json_response({"id": u["tg_id"], "username": u["username"], "first_name": u["first_name"]})


@routes.post("/app/auth/logout")
async def logout(request: web.Request):
    auth = request.headers.get("Authorization", "")
    if auth.startswith("Bearer "):
        with db() as c:
            c.execute("DELETE FROM sessions WHERE token_hash=?", (_hash(auth[7:]),))
    return web.json_response({"ok": True})


@routes.get("/app/orders")
async def orders(request: web.Request):
    u = _session_user(request)
    if not u:
        return _unauth()
    return web.json_response({"orders": fetch_orders(u["tg_id"])})


@routes.post("/app/orders/link")
async def link_order(request: web.Request):
    """Приложение сообщает id заказа, созданного через витрину."""
    u = _session_user(request)
    if not u:
        return _unauth()
    body = await request.json()
    order_id = str(body.get("id", ""))
    if not order_id:
        return web.json_response({"error": "id required"}, status=400)
    # TODO: подтяните заказ с витрины/из своей БД по order_id и сохраните его данные в data,
    # чтобы /app/orders мог его отдавать. Пока сохраняем только привязку.
    import json
    with db() as c:
        c.execute(
            "INSERT OR IGNORE INTO app_orders(order_id, tg_id, data) VALUES(?,?,?)",
            (order_id, u["tg_id"], json.dumps({"id": order_id})),
        )
    return web.json_response({"ok": True})


def make_app() -> web.Application:
    init_db()
    app = web.Application()
    app.add_routes(routes)
    return app


# ------------------------------------------------------------------ интеграция с ботом (aiogram 3)
def register_bot_handlers(dp) -> None:
    """Добавьте в своего бота: register_bot_handlers(dp). Должен стоять ПЕРЕД обычным /start."""
    from aiogram import F
    from aiogram.filters import CommandStart
    from aiogram.types import Message

    @dp.message(CommandStart(deep_link=True), F.text.regexp(r"^/start login_[A-Za-z0-9_-]{20,64}$"))
    async def _login(message: Message):
        token = message.text.split("login_", 1)[1]
        u = message.from_user
        ok = confirm_login(token, u.id, u.username, u.first_name)
        await message.answer(
            "✅ Готово! Возвращайтесь в приложение — вход выполнен."
            if ok else "Ссылка входа устарела. Попробуйте ещё раз в приложении."
        )


# ------------------------------------------------------------------ самостоятельный запуск
async def _main():
    from aiogram import Bot, Dispatcher

    bot = Bot(os.environ["BOT_TOKEN"])
    dp = Dispatcher()
    register_bot_handlers(dp)

    runner = web.AppRunner(make_app())
    await runner.setup()
    await web.TCPSite(runner, "0.0.0.0", int(os.getenv("PORT", "8080"))).start()
    await dp.start_polling(bot)


if __name__ == "__main__":
    asyncio.run(_main())
