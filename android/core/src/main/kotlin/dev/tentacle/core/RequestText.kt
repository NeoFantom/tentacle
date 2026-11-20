package dev.tentacle.core

object RequestText {
    const val MAX_UNITS = 1000
    const val MAX_CHARS = 8000
    const val SCOPE = "tentacle-requests/1"

    val CJK_RANGES: List<IntRange> = listOf(
        0x1100..0x11ff,
        0x2e80..0x2fdf,
        0x3005..0x3005,
        0x3007..0x3007,
        0x3021..0x3029,
        0x3038..0x303b,
        0x3041..0x309f,
        0x30a0..0x30ff,
        0x3130..0x318f,
        0x31f0..0x31ff,
        0x3400..0x4dbf,
        0x4e00..0x9fff,
        0xa960..0xa97f,
        0xac00..0xd7af,
        0xd7b0..0xd7ff,
        0xf900..0xfaff,
        0xff66..0xff9f,
        0x20000..0x2fa1f,
        0x30000..0x3134f,
    )

    fun units(text: String): Int {
        var units = 0
        var inRun = false
        var offset = 0
        while (offset < text.length) {
            val codePoint = text.codePointAt(offset)
            offset += Character.charCount(codePoint)
            when {
                CJK_RANGES.any { codePoint in it } -> {
                    units++
                    inRun = false
                }
                isLetterOrNumber(codePoint) -> {
                    if (!inRun) units++
                    inRun = true
                }
                isMark(codePoint) && inRun -> Unit
                else -> inRun = false
            }
        }
        return units
    }

    fun validate(text: String, allowEmpty: Boolean = false) {
        if (!allowEmpty && text.isEmpty()) {
            throw ApiException(400, "bad_request", "Request text must not be empty")
        }
        if (text.length > MAX_CHARS) {
            throw ApiException(400, "bad_request", "Request text is too long")
        }
        if (text.any { it.code < 0x20 && it != '\n' && it != '\t' } || text.any { it.code in 0x7f..0x9f }) {
            throw ApiException(400, "bad_request", "Request text contains a control character")
        }
        if (units(text) > MAX_UNITS) {
            throw ApiException(400, "bad_request", "Request text exceeds $MAX_UNITS units")
        }
    }

    private fun isMark(codePoint: Int): Boolean = when (Character.getType(codePoint)) {
        Character.NON_SPACING_MARK.toInt(),
        Character.COMBINING_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt(), -> true
        else -> false
    }

    private fun isLetterOrNumber(codePoint: Int): Boolean = when (Character.getType(codePoint)) {
        Character.UPPERCASE_LETTER.toInt(),
        Character.LOWERCASE_LETTER.toInt(),
        Character.TITLECASE_LETTER.toInt(),
        Character.MODIFIER_LETTER.toInt(),
        Character.OTHER_LETTER.toInt(),
        Character.DECIMAL_DIGIT_NUMBER.toInt(),
        Character.LETTER_NUMBER.toInt(),
        Character.OTHER_NUMBER.toInt(), -> true
        else -> false
    }
}
