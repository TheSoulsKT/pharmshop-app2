package ru.pharmshop.app

import android.content.res.ColorStateList
import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import org.json.JSONObject

// Правила чата: текст правил, окно с согласием (при регистрации в чате), проверки текста на телефоне и @упоминания.
// Проверки зеркалят сервер (server/worker.js: chatTextProblem). Модераторы с «галочкой» ограничений не имеют.

/** Длина сообщения в общем чате и личных (как CHAT_MSG_MAX на сервере). */
internal const val CHAT_MSG_MAX = 150

/** Правила чата: значок, заголовок, текст. Первые семь — как в PWA, «Упоминания» добавлено для приложения. */
private val CHAT_RULES = listOf(
    Triple("✉️", "Коротко и без крика", "Не больше 150 символов в одном сообщении. Если в тексте слишком много ЗАГЛАВНЫХ букв, сообщение не отправится."),
    Triple("🔗", "Без ссылок", "Ссылки, адреса сайтов и приглашения (t.me, wa.me и подобные) отправлять нельзя ни в общем чате, ни в личных сообщениях."),
    Triple("🚫", "Что запрещено", "Угрозы и призывы к насилию, призывы к самоубийству, продажа и покупка наркотиков и оружия, мошенничество и поддельные документы, взломы и утечки данных, торговля людьми и любые материалы с детьми сексуального характера. Часть таких сообщений блокируется автоматически."),
    Triple("🤝", "Уважение к людям", "Не оскорбляйте других, не спамьте, не выдавайте себя за модератора и не публикуйте чужие личные данные."),
    Triple("📺", "Каналы", "У каждого канала своя тема. В «Новостях» пишут только модераторы."),
    Triple("@", "Упоминания", "Напишите @ник, чтобы обратиться к человеку: сообщение с упоминанием выделится у него в ленте. Нажмите на @ник в сообщении, чтобы написать этому человеку лично."),
    Triple("🛡️", "Модерация", "За нарушения модераторы могут выдать мут или закрыть доступ к чату. Увидели нарушение — напишите в поддержку."),
    Triple("💬", "Вопросы по VPN и оплате", "Лучше сразу в поддержку (Профиль → Поддержка): там ответят быстрее, и можно прикрепить фото.")
)

/**
 * Окно «Правила чата». need = true — с галочкой согласия (при регистрации): кнопка «Принимаю» активна только после неё
 * и запоминает согласие, затем вызывает [onAccept]. need = false — просто чтение.
 */
internal fun MainActivity.chatRulesSheet(need: Boolean, onAccept: () -> Unit = {}) {
    sheet("📜 Правила чата", scroll = true) { box, close ->
        box.addView(txt("Коротко, чтобы всем было комфортно", 13f, MUTE).apply { layoutParams = lp(12) })
        for ((icon, title, text) in CHAT_RULES) {
            box.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                background = shape(CARD, 16, LINE)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = lp(8)
                addView(txt(icon, 20f, if (icon == "@") BLUE else TEXT, icon == "@").apply {
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(dp(30), LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(10) }
                })
                addView(LinearLayout(this@chatRulesSheet).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    addView(txt(title, 14f, TEXT, true))
                    addView(txt(text, 12f, MUTE).apply { setLineSpacing(0f, 1.1f) })
                })
            })
        }
        if (need) {
            val ok = gbtn("Принимаю", true) { }
            val chk = CheckBox(this).apply {
                text = "Я прочитал(а) правила и обязуюсь их соблюдать"
                setTextColor(TEXT)
                textSize = 13f * Theme.textScale
                buttonTintList = ColorStateList.valueOf(BLUE)
                layoutParams = lp(8)
            }
            ok.alpha = 0.4f
            chk.setOnCheckedChangeListener { _, c -> ok.alpha = if (c) 1f else 0.4f }
            ok.setOnClickListener {
                if (!chk.isChecked) { toast("Отметьте, что прочитали правила"); return@setOnClickListener }
                Store.chatRulesOk = true
                close()
                onAccept()
            }
            box.addView(chk)
            box.addView(btnGrid(listOf(gbtn("Позже") { close() }, ok), 0))
        } else {
            box.addView(btnGrid(listOf(gbtn("Понятно", true) { close() }), 0))
        }
    }
}

// ---------- проверки текста (как на сервере) ----------

private val LINK_RE = Regex(
    """(?:https?://|www\.|\bt\.me/|\bwa\.me/|\btg://|[a-z0-9-]+(?:\.|\[\.\]|\(\.\)|\s(?:dot|точка)\s)(?:com|net|org|ru|su|io|me|info|biz|xyz|top|club|online|site|shop|store|app|dev|cc|tv|ly|gl|by|ua|kz|to|gg|рф)(?![\p{L}\d])|\S+\s+точка\s+(?:ком|ру|нет|орг|инфо)(?![\p{L}]))""",
    RegexOption.IGNORE_CASE   // в Kotlin/JVM IGNORE_CASE уже включает UNICODE_CASE: «РФ», «ТОЧКА» тоже находятся
)
private val URL_RE = Regex("""https?://\S+""")

/** Слишком много заглавных: от 8 букв, и больше 70% из них заглавные (ссылки не считаются). */
internal fun capsBad(t: String): Boolean {
    val letters = URL_RE.replace(t, "").filter { it.isLetter() }
    return letters.length >= 8 && letters.count { it != it.lowercaseChar() }.toFloat() / letters.length > 0.7f
}

/** Что не так с текстом сообщения ("" — всё хорошо). Стоп-слова проверяет только сервер. */
internal fun chatTextProblem(t: String): String = when {
    t.length > CHAT_MSG_MAX -> "Сообщение длиннее $CHAT_MSG_MAX символов"
    capsBad(t) -> "Слишком много заглавных букв — напишите без КАПСА"
    LINK_RE.containsMatchIn(t) -> "Ссылки в чате отправлять нельзя"
    else -> ""
}

// ---------- упоминания ----------

private val MENTION_RE = Regex("""(^|[\s(])@([^\s@<>&.,!?:;()]{2,21})""")

/** Регулярка «@мой_ник» для каждого ника: раньше она компилировалась заново для каждого сообщения при каждой перерисовке ленты. */
private val mentionMeCache = java.util.concurrent.ConcurrentHashMap<String, Regex>()

/** Ник без «@» и в нижнем регистре: так сравниваем упоминания. */
internal fun nickKey(s: String): String = s.removePrefix("@").lowercase()

/** Упоминает ли сообщение меня (@мой_ник) или отвечает ли на моё сообщение. Свои сообщения не считаются. */
internal fun mentionsMe(m: JSONObject, myNick: String): Boolean {
    val n = nickKey(myNick)
    if (n.isEmpty()) return false
    if (m.optLong("reply_to") > 0 && nickKey(m.optString("q_nick")) == n) return true
    val re = mentionMeCache.getOrPut(n) {
        Regex("""(^|[\s(])@""" + Regex.escape(n) + """(?![^\s.,!?:;()])""", RegexOption.IGNORE_CASE)
    }
    return re.containsMatchIn(m.optString("text"))
}

/** Текст сообщения с выделенными @упоминаниями; нажатие на упоминание вызывает [onTap] (ник без «@»). */
internal fun MainActivity.mentionText(body: String, own: Boolean, myNick: String, onTap: (String) -> Unit): CharSequence {
    val out = SpannableString(body)
    for (m in MENTION_RE.findAll(body)) {
        val g = m.groups[2] ?: continue
        val start = g.range.first - 1          // вместе с «@»
        val end = g.range.last + 1
        val nick = g.value
        val col = if (own) Color.BLACK else BLUE
        out.setSpan(object : ClickableSpan() {
            override fun onClick(w: View) { onTap(nick) }
            override fun updateDrawState(ds: TextPaint) {
                ds.color = col
                ds.isFakeBoldText = true
                ds.isUnderlineText = own
            }
        }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (!own && myNick.isNotEmpty() && nickKey(nick) == nickKey(myNick)) {
            out.setSpan(BackgroundColorSpan(ColorUtils.setAlphaComponent(BLUE, 60)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
    return out
}
