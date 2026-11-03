package dev.anded.xmb

import android.content.pm.ApplicationInfo

enum class Glyph { STAR, SETTINGS, CHAT, BANK, MAP, PLAY, BAG, FOOD, GRID, HOME, KEY, RESET, SEARCH, THEME, WAVE, ICONS, WALLPAPER,
    LOOK, MEDIA_PLAY, MEDIA_PAUSE, MEDIA_NEXT, MEDIA_PREV, CALENDAR, BELL, HELP,
    GAMES, PHOTO, NEWS, WORK, BUSINESS, EDU, HEALTH, LIFE, TOOLS,
}

/** Раздел. title — строковый ресурс; always — показывается и пустым («Частые», «Настройки»), остальные пустые скрыты. */
class Category(val id: String, val title: Int, val glyph: Glyph, val always: Boolean = false)

/**
 * Разделы — как в «умном» списке приложений Nothing Launcher (Соцсети, Развлечения, Игры, Мультимедиа,
 * Новости и чтение, Покупки, Финансы, Путешествия, Производительность, Бизнес, Образование, Здоровье,
 * Образ жизни, Утилиты), с короткими подписями для ряда XMB.
 * id старых разделов сохранены — ручные перекладки пользователя не теряются; «Еда» ушла в «Образ жизни».
 */
object Categories {
    /** «Частые» — сами собираются из самых открываемых; переложить приложение туда нельзя. */
    const val TOP = "top"
    const val SETTINGS_ID = "settings"
    const val TOP_INDEX = 0
    const val TOP_SIZE = 10
    const val SETTINGS = 1
    const val OTHER = "other"

    val all = listOf(
        Category(TOP, R.string.sec_top, Glyph.STAR, always = true),
        Category(SETTINGS_ID, R.string.sec_settings, Glyph.SETTINGS, always = true),
        Category("chat", R.string.sec_chat, Glyph.CHAT),
        Category("fun", R.string.sec_fun, Glyph.PLAY),
        Category("games", R.string.sec_games, Glyph.GAMES),
        Category("photo", R.string.sec_photo, Glyph.PHOTO),
        Category("news", R.string.sec_news, Glyph.NEWS),
        Category("shop", R.string.sec_shop, Glyph.BAG),
        Category("banks", R.string.sec_banks, Glyph.BANK),
        Category("maps", R.string.sec_maps, Glyph.MAP),
        Category("work", R.string.sec_work, Glyph.WORK),
        Category("business", R.string.sec_business, Glyph.BUSINESS),
        Category("edu", R.string.sec_edu, Glyph.EDU),
        Category("health", R.string.sec_health, Glyph.HEALTH),
        Category("life", R.string.sec_life, Glyph.LIFE),
        Category("tools", R.string.sec_tools, Glyph.TOOLS),
        Category(OTHER, R.string.sec_other, Glyph.GRID),
    )

    private val LEGACY = mapOf("food" to "life")

    fun indexOf(id: String?): Int {
        val key = LEGACY[id] ?: id
        return all.indexOfFirst { it.id == key }.takeIf { it >= 0 } ?: all.lastIndex
    }

    // Первое совпадение подстроки в имени пакета решает раздел; порядок важен:
    // «Самокат» (ru.sbcs.store) должен попасть в еду раньше, чем «store» — в покупки,
    // эмуляторы и игровые оболочки — в игры раньше, чем «player» — в развлечения.
    private val rules: List<Pair<String, List<String>>> = listOf(
        SETTINGS_ID to listOf("com.android.settings", "com.nothing.settings"),
        "games" to listOf(
            "retroarch", "com.retroarch", "org.dolphinemu", "ppsspp", "duckstation", "aethersx2", "nethersx2",
            "org.citra", "citra", "yuzu", "sudachi", "suyu", "eden", "skyline", "ryujinx", "drastic", "melonds",
            "mupen64", "flycast", "redream", "lemuroid", "daijisho", "org.es_de", "pegasus", "beacon", "winlator",
            "gamehub", "gamenative", "mobox", "vita3k", "azahar", "lime3ds", "mgba", "snes9x", "fpse", "epsxe",
            "valvesoftware", "epicgames", "com.playstation", "com.microsoft.xcloud", "xbox", "nvidia.geforcenow",
            "moonlight", "steamlink", "rpcs3", "com.game", ".game", "games",
        ),
        "chat" to listOf(
            "org.telegram", "org.thunderdog.challegram", "com.whatsapp", "com.viber", "org.thoughtcrime.securesms",
            "com.discord", "com.skype", "ru.oneme", "com.vkontakte", "com.vk.im", "ru.mail.mailapp",
            "ru.yandex.mail", "com.google.android.gm", "com.google.android.apps.messaging", "com.android.mms",
            "protonmail", "io.element", "bitchat", "dialer", "contacts", "messag", "telegram", "facebook.orca",
            "com.facebook.katana", "com.instagram", "com.twitter", "com.x.android", "com.zhiliaoapp", "musically",
            "reddit", "threads", "mastodon", "bluesky", "snapchat", "line.android", "kakao", "wechat", "com.tencent.mm",
            "google.android.apps.tachyon",
        ),
        "banks" to listOf(
            "sberbank", "tinkoff", "ru.tbank", "com.idamob", "alfabank", "ru.vtb", "raiffeisen", "gazprombank",
            "ru.gpb", "rosbank", "psbank", "sovcombank", "pochtabank", "otkritie", "mtsbank", "ru.mts.money",
            "yoomoney", "ru.ozon.fintech", "uralsib", "ubrr", "rshb", "akbars", "homecredit", "revolut", "paypal",
            "transferwise", "wise", "ru.cardsmobile", "walletnfcrel", "unicredit", "ru.nspk.mirpay", "bybit",
            "binance", "coinbase", "trustapp", "metamask", "wallet", "invest", "broker", "bank",
        ),
        "life" to listOf(
            "ru.foodfox", "deliveryclub", "lavka", "ru.sbcs.store", "samokat", "vkusvill", "perekrestok", "ru.x5",
            "pyaterochka", "magnit", "ru.lenta", "auchan", "ru.metro", "kfc", "mcdonalds", "mcdonaldsrussia",
            "vkusnoitochka", "burgerking", "dodopizza", "tanuki", "wolt", "glovo", "ubereats", "doordash", "food",
            "pizza", "sushi", "coffee", "starbucks", "smarthome", "xiaomi.smarthome", "tuya", "smartthings",
            "pinterest", "tinder", "bumble",
        ),
        "maps" to listOf(
            "com.google.android.apps.maps", "yandexmaps", "yandexnavi", "ru.yandex.taxi", "ru.dublgis", "com.waze",
            "osmand", "organicmaps", "mapswithme", "ru.yandex.metro", "ru.yandex.transport", "ubercab", "citymobil",
            "ru.rzd", "aviasales", "tutu", "airbnb", "booking", "tripadvisor", "skyscanner", "strelka", "maps", "navi",
            "taxi", "metro", "transport",
        ),
        "shop" to listOf(
            "com.android.vending", "ru.ozon.app", "wildberries", "ru.beru", "ru.yandex.market", "com.avito",
            "aliexpress", "com.amazon.mshop", "ebay", "lamoda", "megamarket", "ru.dns", "mvideo", "eldorado", "ikea",
            "leroymerlin", "joom", "shein", "temu", "rustore", "ru.vk.store", "market", "shop", "store",
        ),
        "fun" to listOf(
            "youtube", "netflix", "spotify", "ru.yandex.music", "kinopoisk", "ru.ivi", "ru.okko", "ru.kion", "wink",
            "twitch", "vkvideo", "rutube", "soundcloud", "deezer", "tidal", "music", "podcast", "video", "player",
            ".tv", "oculus", "meta.horizon",
        ),
        "photo" to listOf(
            "camera", "gallery", "com.google.android.apps.photos", "snapseed", "lightroom", "picsart", "capcut",
            "vsco", "inshot", "photo", "composer",
        ),
        "news" to listOf(
            "news", "reader", "fbreader", "kindle", "books", "bookmate", "litres", "feedly", "medium", "pocket",
            "flipboard", "inoreader", "ru.yandex.zen", "dzen", "comics", "manga",
        ),
        "edu" to listOf(
            "duolingo", "coursera", "udemy", "khanacademy", "skyeng", "uchi.ru", "quizlet", "memrise", "anki",
            "photomath", "dictionary", "lingualeo", "learn",
        ),
        "health" to listOf(
            "fitness", "health", "strava", "sleep", "workout", "nike.plusgps", "calorie", "yazio",
            "apteka", "gosuslugi.health", "meditat", "headspace", "calm.android", "fit",
        ),
        "business" to listOf(
            "com.slack", "microsoft.teams", "us.zoom", "zoom.videomeetings", "atlassian", "jira", "hh.android",
            "linkedin", "bitrix", "ru.hh", "skype.raider",
        ),
        "work" to listOf(
            "apps.docs", "editors", "keep", "calendar", "office", "notion", "obsidian", "md.obsidian", "todoist",
            "ticktick", "trello", "evernote", "onedrive", "dropbox", "google.android.apps.docs", "notes",
            "deepl", "chatgpt", "openai", "deepseek", "anthropic", "claude", "gemini", "copilot",
        ),
        "tools" to listOf(
            "chrome", "firefox", "mozilla", "browser", "opera", "brave", "duckduckgo", "calculator", "clock", "files",
            "filemanager", "recorder", "soundrecorder", "vpn", "wireguard", "proxy", "authenticator",
            "translate", "battery", "security", "safetyhub", "weather", "windy", "keyboard", "flashlight", "torrent",
            "flud", "googlequicksearchbox", "glyph", "nothing.smartcenter", "scanner", "adm", "launcher", "gosuslugi", "ru.rostel",
        ),
    )

    fun classify(pkg: String, label: String, appCategory: Int): String {
        val p = pkg.lowercase()
        for ((id, needles) in rules) if (needles.any { p.contains(it) }) return id
        when (appCategory) {
            ApplicationInfo.CATEGORY_GAME -> return "games"
            ApplicationInfo.CATEGORY_MAPS -> return "maps"
            ApplicationInfo.CATEGORY_SOCIAL -> return "chat"
            ApplicationInfo.CATEGORY_AUDIO, ApplicationInfo.CATEGORY_VIDEO -> return "fun"
            ApplicationInfo.CATEGORY_IMAGE -> return "photo"
            ApplicationInfo.CATEGORY_NEWS -> return "news"
            ApplicationInfo.CATEGORY_PRODUCTIVITY -> return "work"
            ApplicationInfo.CATEGORY_ACCESSIBILITY -> return "tools"
        }
        val l = label.lowercase()
        if (l.contains("банк") || l.contains("bank")) return "banks"
        return OTHER
    }
}
