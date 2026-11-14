package com.meld.spotify.match

// Common code has no Unicode decomposition, so precomposed letters are folded from this table.
private val LETTER_FOLDS: Map<Char, String> =
    buildMap {
        fun fold(
            base: String,
            variants: String,
        ) = variants.forEach { put(it, base) }
        fold("a", "àáâãäåāăąǎạảấầẩẫậắằẳẵặ")
        fold("ae", "æ")
        fold("c", "çćĉċč")
        fold("d", "ďđð")
        fold("e", "èéêëēĕėęěẹẻẽếềểễệ")
        fold("g", "ĝğġģ")
        fold("h", "ĥħ")
        fold("i", "ìíîïĩīĭįıỉị")
        fold("j", "ĵ")
        fold("k", "ķ")
        fold("l", "ĺļľŀł")
        fold("n", "ñńņňŉ")
        fold("o", "òóôõöøōŏőơọỏốồổỗộớờởỡợ")
        fold("oe", "œ")
        fold("r", "ŕŗř")
        fold("s", "śŝşšș")
        fold("ss", "ß")
        fold("t", "ţťŧț")
        fold("th", "þ")
        fold("u", "ùúûüũūŭůűųưụủứừửữự")
        fold("w", "ŵ")
        fold("y", "ýÿŷỳỵỷỹ")
        fold("z", "źżž")
        fold("α", "ά")
        fold("ε", "έ")
        fold("η", "ή")
        fold("ι", "ίϊΐ")
        fold("ο", "ό")
        fold("υ", "ύϋΰ")
        fold("ω", "ώ")
        fold("σ", "ς")
        fold("е", "ё")
    }

private const val APOSTROPHES = "'’‘ʼ`´"

private val WHITESPACE = Regex("\\s+")

// Combining diacritics of Latin/Greek/Cyrillic and optional Arabic vowel marks. Marks of other scripts
// (Devanagari, Thai, kana voicing) carry meaning and are kept.
private fun Char.isOptionalMark(): Boolean = this in '̀'..'ͯ' || this in 'ً'..'ْ' || this == 'ـ' || this == 'ٰ'

private fun Char.isScriptMark(): Boolean = category == CharCategory.NON_SPACING_MARK || category == CharCategory.COMBINING_SPACING_MARK

/** Full-width forms (common in CJK titles) to ASCII, so brackets and Latin letters parse like any other. */
internal fun foldWidth(text: String): String =
    buildString(text.length) {
        for (char in text) append(if (char in '！'..'～') char - 0xFEE0 else char)
    }

/**
 * Lower-cased letters, digits and script marks of any writing system, single-spaced. Never empty for a
 * non-blank input.
 * shortcut: characters outside the BMP (emoji, rare CJK) are dropped; keep surrogate pairs if that ever matters.
 */
internal fun normalizeText(raw: String): String {
    val out = StringBuilder(raw.length)
    for (char in foldWidth(raw).lowercase()) {
        val folded = LETTER_FOLDS[char]
        when {
            char in APOSTROPHES || char.isOptionalMark() -> Unit
            char == '&' -> out.append(" and ")
            folded != null -> out.append(folded)
            char.isLetterOrDigit() || char.isScriptMark() -> out.append(char)
            else -> out.append(' ')
        }
    }
    // A title made only of symbols ("÷", "!!!") must stay comparable instead of collapsing to nothing.
    return out.trim().replace(WHITESPACE, " ").ifEmpty { raw.trim().lowercase().replace(WHITESPACE, " ") }
}

/** 0..1 similarity of two normalised strings: mean of character-bigram and word overlap (Dice). */
internal fun similarity(
    a: String,
    b: String,
): Double {
    // Empty means "no signal": two empty strings are not a match.
    if (a.isEmpty() || b.isEmpty()) return 0.0
    if (a == b) return 1.0
    val wordsA = a.split(' ').toSet()
    val wordsB = b.split(' ').toSet()
    val score = (dice(a.windowed(2).toSet(), b.windowed(2).toSet()) + dice(wordsA, wordsB)) / 2
    val numbersA = wordsA.filterTo(mutableSetOf()) { it.all(Char::isDigit) }
    val numbersB = wordsB.filterTo(mutableSetOf()) { it.all(Char::isDigit) }
    // "Symphony No. 5" and "Symphony No. 9" share nearly every character yet are different works.
    return if (numbersA.isNotEmpty() && numbersB.isNotEmpty() && numbersA != numbersB) score / 2 else score
}

private fun <T> dice(
    a: Set<T>,
    b: Set<T>,
): Double = if (a.isEmpty() || b.isEmpty()) 0.0 else 2.0 * a.count { it in b } / (a.size + b.size)
