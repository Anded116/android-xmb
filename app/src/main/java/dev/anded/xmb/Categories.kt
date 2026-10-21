package dev.anded.xmb

import android.content.pm.ApplicationInfo

enum class Glyph { SETTINGS, CHAT, BANK, MAP, PLAY, BAG, FOOD, GRID, HOME, KEY, RESET, SEARCH }

class Category(val id: String, val title: String, val glyph: Glyph)

object Categories {
    val all = listOf(
        Category("settings", "Настройки", Glyph.SETTINGS),
        Category("chat", "Связь", Glyph.CHAT),
        Category("banks", "Банки", Glyph.BANK),
        Category("maps", "Карты", Glyph.MAP),
        Category("fun", "Развлечения", Glyph.PLAY),
        Category("shop", "Покупки", Glyph.BAG),
        Category("food", "Еда", Glyph.FOOD),
        Category("other", "Прочее", Glyph.GRID),
    )
    const val SETTINGS = 0
    const val DEFAULT = 1

    fun indexOf(id: String?): Int = all.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: all.lastIndex

    // Первое совпадение подстроки в имени пакета решает раздел; порядок важен:
    // «Самокат» (ru.sbcs.store) должен попасть в еду раньше, чем «store» — в покупки.
    private val rules: List<Pair<String, List<String>>> = listOf(
        "settings" to listOf("com.android.settings", "com.nothing.settings"),
        "chat" to listOf(
            "org.telegram", "org.thunderdog.challegram", "com.whatsapp", "com.viber", "org.thoughtcrime.securesms",
            "com.discord", "com.skype", "us.zoom", "ru.oneme", "com.vkontakte", "com.vk.im", "ru.mail.mailapp",
            "ru.yandex.mail", "com.google.android.gm", "com.google.android.apps.messaging", "com.android.mms",
            "com.slack", "microsoft.teams", "protonmail", "io.element", "bitchat", "dialer", "contacts", "messag",
            "telegram",
        ),
        "banks" to listOf(
            "sberbank", "tinkoff", "ru.tbank", "com.idamob", "alfabank", "ru.vtb", "raiffeisen", "gazprombank",
            "ru.gpb", "rosbank", "psbank", "sovcombank", "pochtabank", "otkritie", "mtsbank", "ru.mts.money",
            "yoomoney", "ru.ozon.fintech", "uralsib", "ubrr", "rshb", "akbars", "homecredit", "revolut", "paypal",
            "transferwise", "ru.cardsmobile", "walletnfcrel", "unicredit", "ru.nspk.mirpay", "bybit", "wallet",
            "bank",
        ),
        "food" to listOf(
            "ru.foodfox", "deliveryclub", "lavka", "ru.sbcs.store", "samokat", "vkusvill", "perekrestok", "ru.x5",
            "pyaterochka", "magnit", "ru.lenta", "auchan", "ru.metro", "kfc", "mcdonalds", "vkusnoitochka",
            "burgerking", "dodopizza", "tanuki", "wolt", "glovo", "ubereats", "food", "pizza", "sushi", "coffee",
        ),
        "maps" to listOf(
            "com.google.android.apps.maps", "yandexmaps", "yandexnavi", "ru.yandex.taxi", "ru.dublgis", "com.waze",
            "osmand", "organicmaps", "mapswithme", "ru.yandex.metro", "ru.yandex.transport", "ubercab", "citymobil",
            "ru.rzd", "aviasales", "tutu", "maps", "navi",
        ),
        "shop" to listOf(
            "com.android.vending", "ru.ozon.app", "wildberries", "ru.beru", "ru.yandex.market", "com.avito",
            "aliexpress", "com.amazon", "ebay", "lamoda", "megamarket", "ru.dns", "mvideo", "eldorado", "ikea",
            "leroymerlin", "joom", "shein", "temu", "market", "shop",
        ),
        "fun" to listOf(
            "youtube", "netflix", "spotify", "ru.yandex.music", "kinopoisk", "ru.ivi", "ru.okko", "ru.kion", "wink",
            "musically", "tiktok", "twitch", "reddit", "vkvideo", "rutube", "soundcloud", "valvesoftware", "instagram",
            "pinterest", "twitter", "podcast", "com.google.android.apps.photos", "gallery", "music", "video",
            "player", "game",
        ),
        // Браузеры объявляют себя «социальными» и без этого правила уходят в «Связь».
        "other" to listOf("chrome", "firefox", "mozilla", "browser"),
    )

    fun classify(pkg: String, label: String, appCategory: Int): String {
        val p = pkg.lowercase()
        for ((id, needles) in rules) if (needles.any { p.contains(it) }) return id
        when (appCategory) {
            ApplicationInfo.CATEGORY_MAPS -> return "maps"
            ApplicationInfo.CATEGORY_SOCIAL -> return "chat"
            ApplicationInfo.CATEGORY_GAME, ApplicationInfo.CATEGORY_AUDIO,
            ApplicationInfo.CATEGORY_VIDEO, ApplicationInfo.CATEGORY_IMAGE -> return "fun"
        }
        val l = label.lowercase()
        if (l.contains("банк") || l.contains("bank")) return "banks"
        return "other"
    }
}
