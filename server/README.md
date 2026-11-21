# Сервер входа через Telegram

1. `pip install aiohttp aiogram`
2. Запуск: `BOT_TOKEN=... BOT_USERNAME=pharmaishop_bot PORT=8080 python app_auth.py`
3. Выставите сервис наружу по **HTTPS** (nginx/caddy) и впишите адрес в
   `app/src/main/java/ru/pharmshop/app/Api.kt` -> `AUTH_BASE`.
4. Если бот у вас уже есть и работает отдельно, не запускайте второго polling-бота:
   импортируйте `app_auth` в него, вызовите `register_bot_handlers(dp)`
   и поднимите `make_app()` рядом.
5. В `fetch_orders()` подключите таблицу заказов вашего бота.
