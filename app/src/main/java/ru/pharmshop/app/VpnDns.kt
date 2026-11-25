package ru.pharmshop.app

/**
 * DNS-провайдеры для VPN. Выбранный провайдер используется:
 *  • как DNS внутри TUN-интерфейса (Builder.addDnsServer);
 *  • как DNS ядра Xray, если подписка не принесла свою секцию «dns».
 * Российские домены при включённом «Российские сайты напрямую» резолвятся через Яндекс DNS напрямую (см. VpnSub.applyPolicies).
 */
object VpnDns {
    class Preset(val title: String, val servers: List<String>, val note: String)

    val PRESETS = listOf(
        Preset("Cloudflare", listOf("1.1.1.1", "1.0.0.1"), "Cloudflare — 1.1.1.1: быстрый, без фильтрации."),
        Preset("Google", listOf("8.8.8.8", "8.8.4.4"), "Google Public DNS — 8.8.8.8: быстрый, без фильтрации."),
        Preset("Quad9", listOf("9.9.9.9", "149.112.112.112"), "Quad9 — 9.9.9.9: блокирует известные вредоносные домены."),
        Preset("AdGuard", listOf("94.140.14.14", "94.140.15.15"), "AdGuard DNS — 94.140.14.14: блокирует рекламу и трекеры на уровне DNS.")
    )

    /** Яндекс DNS (базовый): для .ru/.su/.рф при «Российские сайты напрямую». Запросы к нему идут мимо VPN. */
    val YANDEX = listOf("77.88.8.8", "77.88.8.1")

    fun current(): Preset = PRESETS[Store.vpnDns]
}
