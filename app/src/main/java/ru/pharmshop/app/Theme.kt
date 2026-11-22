package ru.pharmshop.app

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color
import androidx.appcompat.app.AppCompatDelegate

/** Тёмная / светлая / как в системе. Выбор хранится в Store.theme: 1 — тёмная (по умолчанию), 2 — светлая, 0 — системная. */
object Theme {
    @JvmField var light = false

    // тёмная палитра
    val D_BG = Color.parseColor("#121214"); val D_CARD = Color.parseColor("#1D1C21"); val D_LINE = Color.parseColor("#2B2A31")
    val D_TEXT = Color.parseColor("#F2F2F4"); val D_MUTE = Color.parseColor("#9A9DA3")
    // светлая палитра
    val L_BG = Color.parseColor("#F4F4F7"); val L_CARD = Color.parseColor("#FFFFFF"); val L_LINE = Color.parseColor("#E1E1E8")
    val L_TEXT = Color.parseColor("#17171A"); val L_MUTE = Color.parseColor("#6B6F76")
    // акценты: на светлом фоне темнее, чтобы текст читался
    val L_GOLD = Color.parseColor("#C98200"); val L_ORANGE = Color.parseColor("#E0560C"); val L_CYAN = Color.parseColor("#0C93AB")
    val L_GREEN = Color.parseColor("#1E9E45"); val L_AMBER = Color.parseColor("#B7791F"); val L_RED = Color.parseColor("#E0352C")
    val L_VIOLET = Color.parseColor("#7C4DEB"); val L_VIOLET_L = Color.parseColor("#6D3FE0")

    val rippleColor get() = if (light) 0x22000000 else 0x33FFFFFF
    val snowColor get() = if (light) Color.parseColor("#8FA6C4") else Color.WHITE

    /** Вызывать в onCreate после Store.init: определяет, какая тема активна сейчас. */
    fun update(a: Activity) {
        light = when (Store.theme) {
            2 -> true
            1 -> false
            else -> (a.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_YES
        }
    }

    fun nightMode(): Int = when (Store.theme) {
        2 -> AppCompatDelegate.MODE_NIGHT_NO
        1 -> AppCompatDelegate.MODE_NIGHT_YES
        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }
}
