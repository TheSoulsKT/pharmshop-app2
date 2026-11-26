package ru.pharmshop.app

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import org.json.JSONArray
import org.json.JSONObject

// Экран «Мой питомец» (кнопка слева от кота в разделе VPN). Кот живёт на сервере (server/worker.js, /api/pet/*):
// его кормит работающий VPN (см. PetFeed), уровень растёт от купленных подписок, а одежду, домик и миску выбирает уровень.
// Две вкладки: «Питомец» (сцена, миска, прогресс уровня) и «Топ» (лучшие по уровню).

private const val P_MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val P_WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

private val PET_TITLES = listOf("Бродяжка", "Домашний", "Любимчик", "Модник", "Хозяин", "Барон", "Граф", "Князь", "Король", "Император")
private val PET_COLORS = longArrayOf(0xFF9AA0A6, 0xFF34C759, 0xFF22C1DC, 0xFF4F8CFF, 0xFF8B5CF6, 0xFFEC4899, 0xFFFF7A3D, 0xFFFFB020, 0xFFF43F5E, 0xFFFFD54A)

internal fun petTitle(level: Int) = PET_TITLES[(level - 1).coerceIn(0, PET_TITLES.size - 1)]
internal fun petAccent(level: Int) = PET_COLORS[(level - 1).coerceIn(0, PET_COLORS.size - 1)].toInt()

/** Таблица уровней из последнего ответа сервера (нужна для окна «Все уровни»). */
private var petLevelsMem: JSONArray = JSONArray()

private fun petHours(min: Int): String {
    if (min < 1) return "меньше минуты"
    val h = min / 60
    val m = min % 60
    return if (h == 0) "$m мин" else if (m == 0) "$h ч" else "$h ч $m мин"
}

private fun petDays(n: Int): String {
    val m100 = n % 100
    val m10 = n % 10
    val w = if (m100 in 11..14) "дней" else when (m10) { 1 -> "день"; 2, 3, 4 -> "дня"; else -> "дней" }
    return "$n $w"
}

internal fun MainActivity.showPet(tab: Int = 0) {
    onHome = false
    val col = screen(4)
    val sid = screenId
    backAction = { navBack = true; showVpn() }
    backRow(col, "Мой питомец") { navBack = true; showVpn() }

    val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = lp(12) }
    tabs.addView(chip("🐾 Питомец", tab == 0) { if (tab != 0) showPet(0) })
    tabs.addView(chip("🏆 Топ", tab == 1) { if (tab != 1) showPet(1) })
    col.addView(tabs)

    val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    col.addView(body)
    body.addView(tv("Загрузка…", 14f, MUTE))
    setPullRefresh { if (sid == screenId) showPet(tab) }
    io.execute {
        try {
            val r = if (tab == 0) ChatApi.petMe() else ChatApi.petTop()
            ui {
                if (sid != screenId) return@ui
                body.removeAllViews()
                if (tab == 0) petMain(body, sid, r) else petTop(body, r)
                riseIn(col, 2)
            }
        } catch (e: Exception) {
            ui {
                if (sid != screenId) return@ui
                body.removeAllViews()
                petError(body, e, tab)
            }
        }
    }
}

private fun MainActivity.petError(body: LinearLayout, e: Exception, tab: Int) {
    val msg = e.message ?: ""
    if (msg.contains("ник", ignoreCase = true)) {
        // питомец привязан к нику в чате: кота без ника не завести
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = shape(CARD, 20, LINE)
            setPadding(dp(18), dp(22), dp(18), dp(18))
            layoutParams = lp(12)
            addView(txt("🐱", 44f).apply { gravity = Gravity.CENTER })
            addView(txt("Заведите питомца", 18f, TEXT, true).apply { gravity = Gravity.CENTER; layoutParams = lp(6) })
            addView(txt("Питомец привязан к нику в чате: по нику вас видно в Топе. Выберите ник — и кот ваш.", 14f, MUTE).apply {
                gravity = Gravity.CENTER
                layoutParams = lp(14)
            })
            addView(gradBtn("Выбрать ник в чате", BLUE, VIOLET, Color.WHITE) { showCommunity() })
        })
        return
    }
    body.addView(tv(errText(e), 14f, RED))
    body.addView(LinearLayout(this).apply { addView(chip("Повторить", false) { showPet(tab) }) })
}

// ---------- вкладка «Питомец» ----------

private fun MainActivity.petBar(ratio: Float, color: Int): View {
    val r = ratio.coerceIn(0f, 1f)
    return LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        background = shape(BG, 6, LINE)
        layoutParams = LinearLayout.LayoutParams(P_MATCH, dp(10))
        addView(View(this@petBar).apply {
            background = shape(color, 6)
            layoutParams = LinearLayout.LayoutParams(0, P_MATCH, maxOf(r, 0.0001f))
        })
        addView(View(this@petBar).apply { layoutParams = LinearLayout.LayoutParams(0, P_MATCH, maxOf(1f - r, 0.0001f)) })
    }
}

private fun MainActivity.petCard(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = shape(CARD, 20, LINE)
    setPadding(dp(16), dp(14), dp(16), dp(16))
    layoutParams = lp(12)
}

private fun MainActivity.petMain(body: LinearLayout, sid: Int, r: JSONObject) {
    var pet = r.optJSONObject("pet") ?: JSONObject()
    var rank = r.optInt("rank")
    r.optJSONArray("levels")?.let { petLevelsMem = it }
    PetFeed.remember(pet)

    val furIdx = AppIcon.current(this).coerceIn(0, AppIcon.all.size - 1)
    val animOn = Store.uiAnim && FxView.allowed(this)
    val stage = PetStageView(this, animOn).apply { layoutParams = LinearLayout.LayoutParams(P_MATCH, dp(250)) }
    val nameTv = txt("", 20f, TEXT, true).apply { maxLines = 1 }
    val lvlTv = txt("", 13f, MUTE)
    val dyn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val top = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8), dp(8), dp(8), dp(14))
        layoutParams = lp(12)
    }

    val rename = chip("✏", false) { renamePet(pet.optString("name")) { nr -> pet = nr; PetFeed.remember(pet) } }
    top.addView(stage)
    top.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), 0, dp(8), 0)
        addView(nameTv)
        addView(rename.apply { (layoutParams as LinearLayout.LayoutParams).leftMargin = dp(10) })
    })
    top.addView(lvlTv.apply { setPadding(dp(8), dp(2), dp(8), 0) })
    body.addView(top)
    body.addView(dyn)

    fun apply() {
        val level = pet.optInt("level", 1)
        val accent = petAccent(level)
        val cap = pet.optDouble("cap", 80.0).toFloat()
        // пока VPN работает, еда в миске прибавляется сразу, а не раз в 5 минут, когда приходит ответ сервера
        val food = minOf(cap, pet.optDouble("food", 0.0).toFloat() + PetFeed.pendingSec() / 60f)
        val ratio = if (cap > 0f) food / cap else 0f
        val mood = if (food <= 0.05f) "starving" else if (ratio < 0.25f) "hungry" else if (ratio < 0.6f) "ok" else "full"
        val vpnOn = VpnState.state == VpnState.S.ON
        val look = pet.optJSONObject("look")
        stage.setup(
            level,
            look?.optJSONObject("outfit")?.optString("id") ?: "none",
            look?.optJSONObject("house")?.optString("id") ?: "box",
            look?.optJSONObject("bowl")?.optString("id") ?: "plastic",
            mood, ratio, vpnOn && ratio < 0.98f, accent, furIdx
        )
        top.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(mix(accent, 0.2f), CARD)).apply {
            cornerRadius = corner(22)
            setStroke(dp(1), ColorUtils.blendARGB(LINE, accent, 0.4f))
        }
        nameTv.text = pet.optString("name", "Котик")
        lvlTv.text = "Уровень $level из ${pet.optInt("max_level", 10)} · ${petTitle(level)}"
        lvlTv.setTextColor(accent)

        dyn.removeAllViews()

        // миска
        dyn.addView(petCard().apply {
            addView(LinearLayout(this@petMain).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = lp(8)
                addView(txt("🥣 Миска", 15f, TEXT, true).apply { layoutParams = LinearLayout.LayoutParams(0, P_WRAP, 1f) })
                addView(txt("${food.toInt()} из ${cap.toInt()}", 13f, MUTE))
            })
            addView(petBar(ratio, when (mood) { "full" -> GREEN; "ok" -> CYAN; "hungry" -> AMBER; else -> RED }))
            val (txtMood, colMood) = when (mood) {
                "full" -> "Кот сыт и доволен" to GREEN
                "ok" -> "Кот не голоден" to CYAN
                "hungry" -> "Кот проголодался" to AMBER
                else -> "Кот голодает!" to RED
            }
            addView(txt(txtMood, 14f, colMood, true).apply { layoutParams = lp(2).apply { topMargin = dp(10) } })
            val left = if (food <= 0.05f) 0 else (food / 3f * 60f).toInt()   // 3 порции в час — как на сервере
            addView(txt(
                if (vpnOn) "VPN включён — кот ест 🐟" else if (left > 0) "Еды хватит ещё на ${petHours(left)}. Включите VPN, чтобы докормить" else "Включите VPN — кот сразу поест",
                13f, if (vpnOn) GREEN else MUTE
            ))
            addView(txt("1 минута работы VPN — 1 порция в миске. Чем выше уровень, тем больше миска и дольше кот сыт.", 12f, MUTE).apply {
                layoutParams = lp(0).apply { topMargin = dp(8) }
            })
        })

        // уровень
        val paid = pet.optInt("paid_days")
        val nextDays = pet.optInt("next_days")
        val maxLv = pet.optInt("max_level", 10)
        dyn.addView(petCard().apply {
            addView(LinearLayout(this@petMain).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = lp(8)
                addView(txt("⭐ Уровень $level", 15f, TEXT, true).apply { layoutParams = LinearLayout.LayoutParams(0, P_WRAP, 1f) })
                addView(txt("оплачено ${petDays(paid)}", 13f, MUTE))
            })
            if (level < maxLv) {
                val lv = petLevelsMem
                val curDays = lv.optJSONObject(level - 1)?.optInt("days") ?: 0
                val nxtDays = lv.optJSONObject(level)?.optInt("days") ?: (paid + nextDays)
                val span = maxOf(1, nxtDays - curDays)
                addView(petBar((paid - curDays).toFloat() / span, accent))
                addView(txt("До уровня ${level + 1} ещё ${petDays(nextDays)} подписки", 14f, TEXT, true).apply {
                    layoutParams = lp(2).apply { topMargin = dp(10) }
                })
                val nl = pet.optJSONObject("next_look")
                if (nl != null) {
                    addView(txt(
                        "Откроется: ${nl.optJSONObject("outfit")?.optString("name")}, ${nl.optJSONObject("house")?.optString("name")?.lowercase()}, ${nl.optJSONObject("bowl")?.optString("name")?.lowercase()}",
                        13f, MUTE
                    ))
                }
            } else {
                addView(petBar(1f, accent))
                addView(txt("Высший уровень: ваш кот — император 👑", 14f, TEXT, true).apply { layoutParams = lp(2).apply { topMargin = dp(10) } })
            }
            addView(LinearLayout(this@petMain).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = lp(0).apply { topMargin = dp(12) }
                addView(chip("🛒 Купить подписку", true) { showVpn() })
                addView(chip("Все уровни", false) { petLevelsSheet(level) })
            })
            addView(txt("Дни считаются со всех купленных и продлённых подписок. Уровень не пропадает, когда подписка заканчивается.", 12f, MUTE).apply {
                layoutParams = lp(0).apply { topMargin = dp(10) }
            })
        })

        // цифры
        dyn.addView(LinearLayout(this@petMain).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = lp(12)
            val stats = listOf(
                "Место в Топе" to (if (rank > 0) "#$rank" else "—"),
                "Кормили VPN" to petHours(pet.optInt("fed_min")),
                "Оплачено" to petDays(paid)
            )
            for ((k, st) in stats.withIndex()) {
                addView(LinearLayout(this@petMain).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    background = shape(CARD, 14, LINE)
                    setPadding(dp(6), dp(10), dp(6), dp(10))
                    layoutParams = LinearLayout.LayoutParams(0, P_WRAP, 1f).apply { if (k > 0) leftMargin = dp(8) }
                    addView(txt(st.first, 11f, MUTE).apply { gravity = Gravity.CENTER; maxLines = 1 })
                    addView(txt(st.second, 14f, TEXT, true).apply { gravity = Gravity.CENTER; maxLines = 1 })
                })
            }
        })
        dyn.addView(chip("🏆 Открыть Топ", false) { showPet(1) }.apply { (layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(6) })
    }
    apply()

    // новый уровень: конфетти и поздравление (один раз)
    if (PetFeed.levelUp > 0) {
        val lv = PetFeed.levelUp
        PetFeed.levelUp = 0
        stage.post { stage.celebrate() }
        toast("🎉 Кот получил уровень $lv: ${petTitle(lv)}!")
    }

    // VPN включили или выключили — кот начинает или перестаёт есть
    val vl: (VpnState.S, String) -> Unit = { _, _ -> if (sid == screenId) apply() }
    VpnState.listen(vl)
    stage.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {}
        override fun onViewDetachedFromWindow(v: View) { VpnState.unlisten(vl) }
    })

    // раз в полминуты берём свежие данные с сервера, пока экран открыт
    val refresher = object : Runnable {
        override fun run() {
            if (sid != screenId) return
            io.execute {
                try {
                    val nr = ChatApi.petMe()
                    ui {
                        if (sid == screenId) {
                            pet = nr.optJSONObject("pet") ?: pet
                            rank = nr.optInt("rank")
                            PetFeed.remember(pet)
                            apply()
                        }
                    }
                } catch (e: Exception) { /* нет сети — оставляем, что есть */ }
            }
            main.postDelayed(this, 30_000L)
        }
    }
    main.postDelayed(refresher, 30_000L)
}

private fun MainActivity.renamePet(current: String, done: (JSONObject) -> Unit) {
    inputSheet("Имя питомца", "Имя (2–16 символов)", current, "Сохранить", InputType.TYPE_CLASS_TEXT) { n ->
        if (n.length < 2) {
            toast("Имя — от 2 символов")
            false
        } else {
            io.execute {
                try {
                    val r = ChatApi.petRename(n)
                    ui { r.optJSONObject("pet")?.let { done(it) }; showPet(0) }
                } catch (e: Exception) {
                    ui { toast(errText(e)) }
                }
            }
            true
        }
    }
}

/** Окно со всеми десятью уровнями: что открывается на каждом. */
private fun MainActivity.petLevelsSheet(current: Int) {
    val lv = petLevelsMem
    sheet("Уровни питомца", scroll = true) { box, close ->
        for (i in 0 until lv.length()) {
            val l = lv.optJSONObject(i) ?: continue
            val n = l.optInt("n", i + 1)
            val accent = petAccent(n)
            val here = n == current
            box.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = shape(if (here) mix(accent, 0.18f) else CARD, 14, if (here) accent else LINE)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                layoutParams = lp(8)
                addView(LinearLayout(this@petLevelsSheet).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(txt("Уровень $n · ${petTitle(n)}", 15f, if (here) accent else TEXT, true).apply {
                        layoutParams = LinearLayout.LayoutParams(0, P_WRAP, 1f)
                    })
                    val d = l.optInt("days")
                    addView(txt(if (n < current) "✓" else if (here) "сейчас" else if (d == 0) "старт" else "от ${petDays(d)}", 12f, if (n <= current) accent else MUTE))
                })
                addView(txt(
                    "👕 ${l.optJSONObject("outfit")?.optString("name")}\n🏠 ${l.optJSONObject("house")?.optString("name")}\n🥣 ${l.optJSONObject("bowl")?.optString("name")} (на ${l.optInt("cap")} порций)",
                    13f, MUTE
                ).apply { layoutParams = lp(0).apply { topMargin = dp(4) } })
            })
        }
        box.addView(chip("Закрыть", false) { close() })
    }
}

// ---------- вкладка «Топ» ----------

private fun MainActivity.petTop(body: LinearLayout, r: JSONObject) {
    val list = r.optJSONArray("top") ?: JSONArray()
    val me = r.optJSONObject("me")
    body.addView(txt("Лучшие питомцы: сначала уровень, потом оплаченные дни и минуты кормления VPN.", 13f, MUTE).apply { layoutParams = lp(12) })
    if (list.length() == 0) {
        body.addView(petCard().apply {
            addView(txt("Пока здесь пусто", 16f, TEXT, true).apply { layoutParams = lp(4) })
            addView(txt("Кормите кота VPN и покупайте подписку — станьте первым в Топе!", 14f, MUTE))
        })
    }
    for (i in 0 until list.length()) {
        val t = list.optJSONObject(i) ?: continue
        val level = t.optInt("level", 1)
        val accent = petAccent(level)
        val mine = t.optBoolean("me")
        val rank = t.optInt("rank")
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shape(if (mine) mix(accent, 0.16f) else CARD, 16, if (mine) accent else LINE)
            setPadding(dp(12), dp(12), dp(14), dp(12))
            layoutParams = lp(8)
            addView(txt(when (rank) { 1 -> "🥇"; 2 -> "🥈"; 3 -> "🥉"; else -> "#$rank" }, if (rank <= 3) 24f else 15f, if (rank <= 3) TEXT else MUTE, true).apply {
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(44), P_WRAP)
            })
            addView(LinearLayout(this@petTop).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, P_WRAP, 1f)
                addView(nameRow(t.optString("nick") + if (mine) " (вы)" else "", t.optInt("badge") == 1, 15f, t.optInt("crown") == 1))
                addView(txt("🐱 ${t.optString("pet")} · ${t.optJSONObject("look")?.optJSONObject("outfit")?.optString("name") ?: ""}", 12f, MUTE).apply { maxLines = 1 })
            })
            addView(LinearLayout(this@petTop).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END
                addView(txt("Ур. $level", 14f, accent, true))
                addView(txt(petDays(t.optInt("paid_days")), 11f, MUTE))
            })
        })
    }
    if (me != null) {
        val mr = me.optInt("rank")
        val inList = (0 until list.length()).any { list.optJSONObject(it)?.optBoolean("me") == true }
        if (!inList) {
            body.addView(petCard().apply {
                addView(txt(
                    if (mr > 0) "Вы на месте #$mr · уровень ${me.optInt("level")}" else "Вас пока нет в Топе",
                    15f, TEXT, true
                ).apply { layoutParams = lp(4) })
                addView(txt(
                    if (mr > 0) "Повышайте уровень подпиской и кормите кота VPN, чтобы подняться выше." else "Включите VPN или купите подписку — и кот появится в списке.",
                    13f, MUTE
                ))
            })
        }
    }
    body.addView(chip("🐾 К моему питомцу", false) { showPet(0) })
}