package com.rousev.steamify

/**
 * Physical keyboard layouts for the on-screen keyboard. Four rows each (number row to bottom letter row),
 * unshifted and shifted, one token per key. Tokens are space separated where a key types more than one
 * character (Arabic lam-alef) or the row has no ASCII separators, otherwise one character per key.
 * Dead keys (´ ^ ¨) type themselves; there is no accent composition.
 */
class Layout(val code: String, val label: String, val name: String, val lower: List<List<String>>, val upper: List<List<String>>)

private fun keys(row: String) = if (' ' in row.trim()) row.trim().split(Regex(" +")) else row.map { it.toString() }

private fun layout(code: String, label: String, name: String, vararg rows: Pair<String, String>) =
    Layout(code, label, name, rows.map { keys(it.first) }, rows.map { keys(it.second) })

object Layouts {
    val all = listOf(
        layout("en", "EN", "English",
            "`1234567890-=" to "~!@#$%^&*()_+",
            "qwertyuiop[]\\" to "QWERTYUIOP{}|",
            "asdfghjkl;'" to "ASDFGHJKL:\"",
            "zxcvbnm,./" to "ZXCVBNM<>?",
        ),
        // Phonetic (traditional): letters sit on their Latin look-alikes, the layout most Bulgarians type on
        layout("bg", "BG", "Български",
            "ч1234567890-=" to "Ч!@№$%€§*()–+",
            "явертъуиопшщю" to "ЯВЕРТЪУИОПШЩЮ",
            "асдфгхйкл;'" to "АСДФГХЙКЛ:\"",
            "зьцжбнм,./" to "ЗѝЦЖБНМ„“?",
        ),
        layout("ru", "RU", "Русский",
            "ё1234567890-=" to "Ё!\"№;%:?*()_+",
            "йцукенгшщзхъ\\" to "ЙЦУКЕНГШЩЗХЪ/",
            "фывапролджэ" to "ФЫВАПРОЛДЖЭ",
            "ячсмитьбю." to "ЯЧСМИТЬБЮ,",
        ),
        layout("uk", "UA", "Українська",
            "'1234567890-=" to "₴!\"№;%:?*()_+",
            "йцукенгшщзхїґ" to "ЙЦУКЕНГШЩЗХЇҐ",
            "фівапролджє" to "ФІВАПРОЛДЖЄ",
            "ячсмитьбю." to "ЯЧСМИТЬБЮ,",
        ),
        layout("de", "DE", "Deutsch",
            "^1234567890ß´" to "°!\"§$%&/()=?`",
            "qwertzuiopü+" to "QWERTZUIOPÜ*",
            "asdfghjklöä#" to "ASDFGHJKLÖÄ'",
            "<yxcvbnm,.-" to ">YXCVBNM;:_",
        ),
        layout("fr", "FR", "Français",
            "² & é \" ' ( - è _ ç à ) =" to "³ 1 2 3 4 5 6 7 8 9 0 ° +",
            "azertyuiop^$" to "AZERTYUIOP¨£",
            "qsdfghjklmù*" to "QSDFGHJKLM%µ",
            "<wxcvbn,;:!" to ">WXCVBN?./§",
        ),
        layout("es", "ES", "Español",
            "º1234567890'¡" to "ª!\"·$%&/()=?¿",
            "qwertyuiop`+" to "QWERTYUIOP^*",
            "asdfghjklñ´ç" to "ASDFGHJKLÑ¨Ç",
            "<zxcvbnm,.-" to ">ZXCVBNM;:_",
        ),
        layout("tr", "TR", "Türkçe",
            "\"1234567890*-" to "é!'^+%&/()=?_",
            "qwertyuıopğü," to "QWERTYUIOPĞÜ;",
            "asdfghjklşi" to "ASDFGHJKLŞİ",
            "<zxcvbnmöç." to ">ZXCVBNMÖÇ:",
        ),
        layout("ar", "AR", "العربية",
            "ذ 1 2 3 4 5 6 7 8 9 0 - =" to "ّ ! @ # $ % ^ & * ) ( _ +",
            "ض ص ث ق ف غ ع ه خ ح ج د \\" to "َ ً ُ ٌ لإ إ ‘ ÷ × ؛ < > |",
            "ش س ي ب ل ا ت ن م ك ط" to "ِ ٍ ] [ لأ أ ـ ، / : \"",
            "ئ ء ؤ ر لا ى ة و ز ظ" to "~ ْ } { لآ آ ’ , . ؟",
        ),
        // SI-1452. Shift gives Latin capitals like the Windows layout; brackets and parens mirror for RTL
        layout("he", "HE", "עברית",
            ";1234567890-=" to "~!@#$%^&*)(_+",
            "/'קראטוןםפ][\\" to "QWERTYUIOP}{|",
            "שדגכעיחלךף," to "ASDFGHJKL:\"",
            "זסבהנמצתץ." to "ZXCVBNM><?",
        ),
        layout("fa", "FA", "فارسی",
            "÷ ۱ ۲ ۳ ۴ ۵ ۶ ۷ ۸ ۹ ۰ - =" to "× ! ٬ ٫ ﷼ ٪ × ، * ) ( ـ +",
            "ض ص ث ق ف غ ع ه خ ح ج چ پ" to "ْ ٌ ٍ ً ُ ِ َ ّ ] [ } { |",
            "ش س ی ب ل ا ت ن م ک گ" to "ؤ ئ ي إ أ آ ة » « : ؛",
            "ظ ط ز ر ذ د ئ و . /" to "ك ٓ ژ ٰ ‌ ٔ ء > < ؟",
        ),
    )

    fun byCode(code: String) = all.firstOrNull { it.code == code }
}
