package ru.pharmshop.app

import android.view.Gravity

/** Что нового: список изменений по версиям. Новую версию добавляйте первой. */
object Changelog {
    class Item(val emoji: String, val title: String, val text: String)
    class Release(val version: String, val items: List<Item>)

    val releases: List<Release> = listOf(
        Release("1.0.5-stable", listOf(
            Item("🐱", "Иконка приложения", "Шесть котов на выбор: Настройки → Оформление."),
            Item("📊", "Скорость и пинг", "Проверка скорости и задержки до сервера VPN прямо в разделе VPN."),
            Item("🔔", "Уведомления VPN", "Предупредим, когда подписка или трафик подходят к концу."),
            Item("🧩", "Виджет VPN", "Остаток дней и трафика на рабочем столе. Добавляется как обычный виджет."),
            Item("🐞", "Сообщить об ошибке", "Одна кнопка: Настройки → Прочее. Отчёт уйдёт в поддержку."),
            Item("🎨", "Новые окна", "Шторки в цвет темы вместо серых системных окон."),
            Item("⚙️", "Настройки по категориям", "Оформление, безопасность и прочее. Размер текста, стартовая вкладка, плотность фона и другое."),
            Item("🔒", "Больше защиты", "Скрытие сумм и ников, подтверждение оплаты, PIN для выхода из VPN, блокировка при сворачивании."),
            Item("🔄", "Обновление свайпом", "Потяните экран вниз в VPN и профиле. При ошибке загрузки VPN есть кнопка «Повторить».")
        ))
    )
}

/** Окно «Что нового»: только последняя версия или вся история. */
internal fun MainActivity.showWhatsNew(all: Boolean = false) {
    val list = if (all) Changelog.releases else Changelog.releases.take(1)
    val title = if (all) "Что нового" else "Что нового в ${list.first().version}"
    sheet(title, scroll = true) { box, close ->
        for (r in list) {
            if (all) box.addView(txt("Версия ${r.version}", 14f, BLUE, true).apply { layoutParams = lp(8) })
            for (item in r.items) {
                box.addView(android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    layoutParams = lp(10)
                    addView(txt(item.emoji, 20f, TEXT).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                        layoutParams = android.widget.LinearLayout.LayoutParams(dp(34), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
                    })
                    addView(android.widget.LinearLayout(this@showWhatsNew).apply {
                        orientation = android.widget.LinearLayout.VERTICAL
                        layoutParams = android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        addView(txt(item.title, 15f, TEXT, true))
                        addView(txt(item.text, 13f, MUTE))
                    })
                })
            }
        }
        box.addView(btnGrid(listOf(gbtn("Понятно", true) { close() }), 0).apply {
            (layoutParams as android.widget.LinearLayout.LayoutParams).topMargin = dp(6)
        })
    }
}

/**
 * После обновления один раз показывает «Что нового». На свежей установке не показывает.
 * Вызывается при возврате в приложение и после разблокировки; ничего не делает, если сейчас что-то перекрывает экран.
 */
internal fun MainActivity.maybeShowWhatsNew() {
    if (!AppState.visible || lockView != null || forceShown) return
    if (lockEnabled() && !AppState.unlocked) return
    val code = BuildConfig.VERSION_CODE
    val seen = Store.seenCode
    if (seen == code) return
    Store.seenCode = code
    if (seen == -1 && !Store.introSeen) return   // первая установка: заставка, а не список изменений
    showWhatsNew()
}
