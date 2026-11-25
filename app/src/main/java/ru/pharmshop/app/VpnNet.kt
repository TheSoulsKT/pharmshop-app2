package ru.pharmshop.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Какая сеть лежит под VPN. Туннели других VPN не считаются; пока наш VPN включён, смотрим именно на сети под ним. */
object VpnNet {
    const val NONE = 0
    const val WIFI = 1
    const val CELL = 2
    const val OTHER = 3

    @Suppress("DEPRECATION")
    fun kind(ctx: Context): Int = try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        var wifi = false; var cell = false; var other = false
        for (n in cm.allNetworks) {
            val c = cm.getNetworkCapabilities(n) ?: continue
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            if (!c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
            when {
                c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> wifi = true
                c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> cell = true
                else -> other = true
            }
        }
        when { wifi -> WIFI; cell -> CELL; other -> OTHER; else -> NONE }
    } catch (e: Exception) { NONE }

    fun name(kind: Int): String = when (kind) { WIFI -> "Wi‑Fi"; CELL -> "мобильная сеть"; OTHER -> "другая сеть"; else -> "нет сети" }

    fun hasInternet(ctx: Context): Boolean = kind(ctx) != NONE
}
