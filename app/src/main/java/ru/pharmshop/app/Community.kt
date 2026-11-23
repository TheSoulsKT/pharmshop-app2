package ru.pharmshop.app

import android.graphics.Color
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

// Вкладка «Чат»: общий чат (одна комната) и личные сообщения. Каждый пользователь выбирает уникальный ник.
// Сервер: server/worker.js (таблицы users, room_messages, dm_messages).

/** Правила приватности личных чатов (Настройки → Прочее → «Приватность чатов», регистрация ника, экран переписки). */
internal const val CHAT_PRIVACY_TEXT =
    "3-тие лица не видят ваши личные чаты, они остаются в пределах приложения и администрация может получить к ним доступ."

/** Состояние вкладки «Чат», которое переживает переходы между экранами. */
internal object CommunityState {
    var tab = 0            // 0 — общий чат, 1 — личные
    var pane = 0           // номер текущей ленты: устаревшие ленты перестают опрашивать сервер
    var myUid = 0L
}

internal fun MainActivity.showChatPrivacy() {
    sheet("Правила приватности") { box, close ->
        box.addView(txt(CHAT_PRIVACY_TEXT, 14f, TEXT).apply { layoutParams = lp(16) })
        box.addView(btnGrid(listOf(gbtn("Понятно", true) { close() }), 0))
    }
}

/** Цвет ника в ленте: у одного человека всегда один и тот же. */
private fun MainActivity.nickColor(nick: String): Int {
    val palette = listOf(BLUE, GREEN, AMBER, PINK, VIOLET_L, CYAN, ORANGE)
    return palette[(nick.hashCode() and 0x7fffffff) % palette.size]
}

/** Ник с галочкой (если она есть). */
internal fun MainActivity.nameRow(nick: String, badge: Boolean, size: Float = 12f): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(txt(nick, size, nickColor(nick), true))
        if (badge) addView(txt(" ✓", size, BLUE, true))
    }

// ---------- вкладка ----------

internal fun MainActivity.showCommunity() {
    onHome = false
    val root = chatScreen(5)
    val sid = screenId
    titleRow(root, "Чат")
    val body = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
    }
    root.addView(body)
    body.addView(tv("Загрузка…", 14f, MUTE))
    io.execute {
        try {
            val me = ChatApi.chatMe()
            ui {
                if (sid != screenId) return@ui
                body.removeAllViews()
                if (me.optBoolean("registered")) communityMain(body, sid, me) else registerView(body)
            }
        } catch (e: Exception) {
            ui {
                if (sid != screenId) return@ui
                body.removeAllViews()
                body.addView(tv(errText(e), 14f, RED))
                body.addView(LinearLayout(this).apply { addView(chip("Повторить", false) { showCommunity() }) })
            }
        }
    }
}

/** Первый вход: выбор ника. Занятый ник сервер не отдаёт. */
private fun MainActivity.registerView(body: LinearLayout) {
    val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    inner.addView(txt("Выберите ник", 18f, TEXT, true).apply { layoutParams = lp(6) })
    inner.addView(txt(
        "Под ним вас увидят в общем чате и личных сообщениях. Ник уникален: двух одинаковых быть не может.",
        14f, MUTE
    ).apply { layoutParams = lp(8) })
    inner.addView(txt(
        "3–20 символов: латиница или кириллица (не вперемешку), цифры и «_». Менять ник можно раз в 7 дней.",
        12f, MUTE
    ).apply { layoutParams = lp(14) })
    val field = input("Ник", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS).apply {
        setSingleLine()
        filters = arrayOf(InputFilter.LengthFilter(21))
    }
    val info = txt("", 13f, RED).apply { layoutParams = lp(8) }
    var working = false
    inner.addView(field)
    inner.addView(info)
    inner.addView(btn("Занять ник", true) {
        if (working) return@btn
        val nick = field.text.toString().trim().removePrefix("@")
        if (nick.isEmpty()) return@btn
        working = true
        info.text = ""
        val sid = screenId
        io.execute {
            try {
                ChatApi.chatRegister(nick)
                ui { working = false; if (sid == screenId) showCommunity() }
            } catch (e: Exception) {
                ui { working = false; if (sid == screenId) info.text = errText(e) }
            }
        }
    })
    inner.addView(txt("🔒 $CHAT_PRIVACY_TEXT", 12f, MUTE))
    body.addView(ScrollView(this).apply {
        isVerticalScrollBarEnabled = false
        addView(inner)
    })
}

private fun MainActivity.changeNickSheet(current: String, done: (String) -> Unit) {
    inputSheet(
        "Сменить ник", "Новый ник", current, "Сменить",
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    ) { v ->
        val nick = v.trim().removePrefix("@")
        if (nick.isEmpty()) {
            false
        } else {
            io.execute {
                try {
                    val r = ChatApi.chatRegister(nick)
                    ui { done(r.optString("nick", nick)); toast("Ник изменён") }
                } catch (e: Exception) {
                    ui { toast(errText(e)) }
                }
            }
            true
        }
    }
}

private fun MainActivity.communityMain(body: LinearLayout, sid: Int, me: JSONObject) {
    val myUid = me.optLong("uid")
    var myNick = me.optString("nick")
    var myBadge = me.optInt("badge") == 1
    CommunityState.myUid = myUid
    var unread = 0
    var renderChats: ((JSONObject) -> Unit)? = null
    var dmStatus: TextView? = null
    var lastChats: JSONObject? = null
    val timeFmt = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())
    val openDm: (Long, String) -> Unit = { uid, nick -> showDm(uid, nick) }

    // шапка: свой ник и его смена
    val who = txt("", 13f, MUTE).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
    fun renderWho() { who.text = "Ваш ник: $myNick" + if (myBadge) "  ✓" else "" }
    renderWho()
    body.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = lp(8)
        addView(who)
        addView(chip("Сменить ник", false) { changeNickSheet(myNick) { myNick = it; renderWho() } }.apply {
            (layoutParams as LinearLayout.LayoutParams).rightMargin = 0
        })
    })

    val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = lp(10) }
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
    }
    var selectTab: (Int) -> Unit = {}
    fun renderTabs() {
        tabs.removeAllViews()
        tabs.addView(chip("Общий чат", CommunityState.tab == 0) { selectTab(0) })
        tabs.addView(chip(if (unread > 0) "Личные ($unread)" else "Личные", CommunityState.tab == 1) { selectTab(1) })
    }

    // ----- общий чат -----
    fun roomPane(alive: () -> Boolean): LinearLayout = msgPane(
        sid, myUid, "Здесь пока тихо. Напишите первым!", showNames = true,
        fetch = { after, rev -> ChatApi.roomMessages(after, rev) },
        send = { t -> ChatApi.roomSend(t) },
        alive = alive,
        onName = { m -> if (m.optLong("uid") != myUid) openDm(m.optLong("uid"), m.optString("nick")) },
        onLong = { m -> roomActions(m, myUid, myBadge, openDm) },
        onData = { r ->
            r.optJSONObject("me")?.let {
                val b = it.optInt("badge") == 1
                if (b != myBadge) { myBadge = b; renderWho() }
            }
        }
    )

    // ----- личные чаты -----
    fun dmPane(): LinearLayout {
        val pane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        pane.addView(txt("🔒 Правила приватности", 12f, BLUE).apply {
            layoutParams = lp(8)
            isClickable = true
            setOnClickListener { showChatPrivacy() }
        })
        pane.addView(LinearLayout(this).apply {
            layoutParams = lp(10)
            addView(chip("✉ Написать по нику", false) {
                inputSheet(
                    "Написать по нику", "Ник", "", "Найти",
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                ) { v ->
                    if (v.isEmpty()) {
                        false
                    } else {
                        io.execute {
                            try {
                                val r = ChatApi.dmFind(v)
                                ui { openDm(r.optLong("uid"), r.optString("nick")) }
                            } catch (e: Exception) {
                                ui { toast(errText(e)) }
                            }
                        }
                        true
                    }
                }
            })
        })
        val status = txt("Загружаем…", 13f, MUTE).apply { layoutParams = lp(8) }
        dmStatus = status
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pane.addView(status)
        pane.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(list)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        })
        renderChats = { r ->
            val arr = r.optJSONArray("chats") ?: JSONArray()
            status.text = if (arr.length() == 0) "Личных чатов пока нет. Нажмите на ник в общем чате или найдите человека по нику." else ""
            list.removeAllViews()
            for (i in 0 until arr.length()) {
                val c = arr.getJSONObject(i)
                val un = c.optInt("unread")
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = ripple(CARD, 18, if (un > 0) BLUE else LINE)
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                    layoutParams = lp(10)
                    isClickable = true
                    setOnClickListener { openDm(c.optLong("uid"), c.optString("nick")) }
                    pressable(this)
                }
                val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                top.addView(nameRow(c.optString("nick"), c.optInt("badge") == 1, 16f).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                if (un > 0) {
                    top.addView(txt("$un", 12f, Color.BLACK, true).apply {
                        background = shape(BLUE, 10)
                        setPadding(dp(8), dp(2), dp(8), dp(2))
                    })
                }
                card.addView(top)
                val mine = c.optLong("sender") == myUid
                card.addView(txt((if (mine) "Вы: " else "") + c.optString("text"), 14f, MUTE).apply {
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                card.addView(txt(timeFmt.format(java.util.Date(c.optLong("ts"))), 11f, MUTE))
                list.addView(card)
            }
        }
        lastChats?.let { renderChats?.invoke(it) }
        return pane
    }

    selectTab = { i ->
        CommunityState.tab = i
        val my = ++CommunityState.pane
        renderTabs()
        content.removeAllViews()
        if (i == 0) {
            renderChats = null
            content.addView(roomPane { CommunityState.pane == my })
        } else {
            content.addView(dmPane())
        }
    }

    // непрочитанные личные: обновляются раз в несколько секунд, пока открыта вкладка
    fun pollChats() {
        if (sid != screenId) return
        io.execute {
            try {
                val r = ChatApi.dmList()
                ui {
                    if (sid != screenId) return@ui
                    lastChats = r
                    unread = r.optInt("unread")
                    renderTabs()
                    renderChats?.invoke(r)
                }
            } catch (e: Exception) {
                ui { if (sid == screenId) dmStatus?.text = errText(e) }
            }
            main.postDelayed({ pollChats() }, 6000L)
        }
    }

    body.addView(tabs)
    body.addView(content)
    selectTab(CommunityState.tab)
    pollChats()
}

/** Меню по долгому нажатию на сообщение в общем чате. */
private fun MainActivity.roomActions(m: JSONObject, myUid: Long, myBadge: Boolean, openDm: (Long, String) -> Unit) {
    val uid = m.optLong("uid")
    val nick = m.optString("nick")
    val own = uid == myUid
    val items = ArrayList<Pair<String, () -> Unit>>()
    fun run(done: String, call: () -> Unit) {
        io.execute {
            try { call(); ui { toast(done) } } catch (e: Exception) { ui { toast(errText(e)) } }
        }
    }
    if (!own) items.add("✉ Написать в личку" to { openDm(uid, nick) })
    if (own || myBadge) items.add("🗑 Удалить сообщение" to { run("Сообщение удалено") { ChatApi.roomDelete(m.optLong("id")) } })
    if (myBadge && !own && m.optInt("badge") != 1) {
        items.add("🔇 Заглушить на час" to { run("$nick заглушён на час") { ChatApi.roomMute(uid, 60) } })
        items.add("🔇 Заглушить на сутки" to { run("$nick заглушён на сутки") { ChatApi.roomMute(uid, 1440) } })
        items.add("🔇 Заглушить на неделю" to { run("$nick заглушён на неделю") { ChatApi.roomMute(uid, 10080) } })
        items.add("🔊 Снять ограничение" to { run("Ограничение снято") { ChatApi.roomMute(uid, 0) } })
    }
    if (items.isEmpty()) return
    listSheet(nick, items.map { it.first }) { i -> items[i].second() }
}

// ---------- личная переписка ----------

internal fun MainActivity.showDm(peer: Long, nick: String) {
    onHome = false
    val root = chatScreen(5)
    val sid = screenId
    CommunityState.tab = 1
    backAction = { showCommunity() }
    backRow(root, nick) { showCommunity() }
    root.addView(txt("🔒 Правила приватности", 12f, BLUE).apply {
        layoutParams = lp(8)
        isClickable = true
        setOnClickListener { showChatPrivacy() }
    })
    root.addView(msgPane(
        sid, CommunityState.myUid, "Переписки пока нет. Напишите первым.", showNames = false, receipts = true,
        fetch = { after, _ -> ChatApi.dmMessages(peer, after) },
        send = { t -> ChatApi.dmSend(peer, t) }
    ))
}

// ---------- лента сообщений ----------

/**
 * Лента + поле ввода для общего чата, личных переписок и админского просмотра.
 * fetch(after, rev) возвращает {messages: [{id, uid, nick, badge, text, ts}], reset?, rev?, peer_read?};
 * send == null — режим просмотра (поля ввода нет). Свои сообщения (uid == myUid) выводятся справа.
 */
internal fun MainActivity.msgPane(
    sid: Int, myUid: Long, hint: String, showNames: Boolean,
    fetch: (Long, Long) -> JSONObject,
    send: ((String) -> Unit)?,
    receipts: Boolean = false,
    namesOnOwn: Boolean = false,
    alive: () -> Boolean = { true },
    onName: ((JSONObject) -> Unit)? = null,
    onLong: ((JSONObject) -> Unit)? = null,
    onData: (JSONObject) -> Unit = {}
): LinearLayout {
    val pane = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
    }
    val list = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(4), 0, dp(8))
    }
    val emptyHint = tv(hint, 14f, MUTE)
    list.addView(emptyHint)
    val scroll = ScrollView(this).apply {
        addView(list)
        isVerticalScrollBarEnabled = false
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
    }
    val status = txt("", 12f, MUTE).apply { layoutParams = lp(4) }
    val timeFmt = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())

    var lastId = 0L
    var rev = -1L           // ревизия ленты (общий чат): при удалении сообщений сервер меняет её, и лента перечитывается
    var peerRead = 0L       // до какого сообщения собеседник прочитал переписку
    val marks = ArrayList<Triple<Long, TextView, String>>()

    fun updateMarks() {
        for ((id, v, base) in marks) {
            val read = peerRead >= id
            v.text = base + if (read) "  ✓✓ Прочитано" else "  ✓ Доставлено"
            v.setTextColor(if (read) BLUE else MUTE)
        }
    }

    fun add(m: JSONObject) {
        val id = m.optLong("id")
        if (id <= lastId) return
        lastId = id
        if (emptyHint.parent != null) list.removeView(emptyHint)
        val own = m.optLong("uid") == myUid
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (own) Gravity.END else Gravity.START
            layoutParams = lp(8)
        }
        if (showNames && (!own || namesOnOwn)) {
            wrap.addView(nameRow(m.optString("nick"), m.optInt("badge") == 1).apply {
                if (onName != null && !own) {
                    isClickable = true
                    setOnClickListener { onName(m) }
                }
            })
        }
        wrap.addView(TextView(this).apply {
            text = m.optString("text")
            textSize = 15f * Theme.textScale
            setTextColor(if (own) Color.BLACK else TEXT)
            background = shape(if (own) BLUE else CARD, 16, if (own) BLUE else LINE)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            maxWidth = (resources.displayMetrics.widthPixels * 0.78f).toInt()
            if (onLong != null) setOnLongClickListener { onLong(m); true }
        })
        val timeStr = timeFmt.format(java.util.Date(m.optLong("ts")))
        val timeView = txt(timeStr, 10f, MUTE)
        wrap.addView(timeView)
        if (receipts && own) marks.add(Triple(id, timeView, timeStr))
        if (onLong != null) wrap.setOnLongClickListener { onLong(m); true }
        list.addView(wrap)
    }

    fun fetchOnce() {
        io.execute {
            try {
                val r = fetch(lastId, rev)
                ui {
                    if (sid != screenId || !alive()) return@ui
                    onData(r)
                    val reset = r.optBoolean("reset")
                    val before = lastId
                    if (reset) {
                        list.removeAllViews()
                        list.addView(emptyHint)
                        lastId = 0
                        marks.clear()
                    }
                    rev = r.optLong("rev", rev)
                    peerRead = r.optLong("peer_read", peerRead)
                    val arr = r.optJSONArray("messages") ?: JSONArray()
                    for (i in 0 until arr.length()) add(arr.getJSONObject(i))
                    updateMarks()
                    status.text = ""
                    if (lastId != before) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                }
            } catch (e: ChatAuthException) {
                Store.adminToken = ""
                ui { if (sid == screenId) showAdminLogin() }
            } catch (e: Exception) {
                ui { if (sid == screenId && alive()) status.text = errText(e) }
            }
        }
    }

    fun loop() {
        if (sid != screenId || !alive()) return
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) fetchOnce()
        main.postDelayed({ loop() }, 4000L)
    }

    pane.addView(scroll)
    pane.addView(status)
    if (send != null) {
        val field = input(
            "Сообщение",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        ).apply {
            maxLines = 4
            filters = arrayOf(InputFilter.LengthFilter(1000))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) }
        }
        fun sendNow() {
            val text = field.text.toString().trim()
            if (text.isEmpty()) return
            field.setText("")
            io.execute {
                try {
                    send(text)
                    ui { fetchOnce() }
                } catch (e: Exception) {
                    ui {
                        if (sid == screenId) {
                            field.setText(text)
                            toast(errText(e))
                        }
                    }
                }
            }
        }
        pane.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            addView(field)
            addView(chip("Отправить", true) { sendNow() }.apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(50))
            })
        })
    }
    loop()
    return pane
}
