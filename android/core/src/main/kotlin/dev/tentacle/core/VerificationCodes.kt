package dev.tentacle.core

data class VerificationCodeMatch(
    val code: String,
    val sender: String,
    val blockedPayment: Boolean,
)

/** Extracts verification codes without depending on Android framework classes. */
object VerificationCodes {
    private val keyword = Regex(
        "验证码|校验码|动态码|动态密码|确认码|安全码|登录码|激活码|认证码|短信码|" +
            "\\b(?:verification\\s+code|security\\s+code|one(?:-|\\s+)time|passcode|code|otp|pin)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val payment = Regex(
        "支付|付款|转账|汇款|提现|消费|交易密码|" +
            "\\b(?:payments?|transfers?|withdraw(?:als?|ing|s)?|purchases?)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val compactCandidate = Regex("(?<![A-Za-z0-9])(?=[A-Za-z0-9]{4,8}(?![A-Za-z0-9]))[A-Za-z0-9]*[0-9][A-Za-z0-9]*(?![A-Za-z0-9])")
    private val spacedDigits = Regex("(?<![0-9])[0-9]{3} [0-9]{3}(?![0-9])")
    private val signature = Regex(
        "^\\s*[【\\[]([^】\\]\\r\\n]{1,40})[】\\]]|[【\\[]([^】\\]\\r\\n]{1,40})[】\\]]\\s*[。.!！]?\\s*$",
        RegexOption.MULTILINE,
    )
    private val longNumber = Regex("(?<![0-9])(?:1[3-9][0-9]{9}|[0-9]{9,})(?![0-9])")
    private val dateOrTime = Regex("(?:[12][0-9]{3}[-/.年][0-9]{1,2}(?:[-/.月][0-9]{1,2})?|[0-9]{1,2}:[0-9]{2})")
    private val amount = Regex(
        "(?:[¥￥$]|USD|RMB)\\s*[0-9]+(?:\\.[0-9]+)?|" +
            "[0-9]+(?:\\.[0-9]+)?\\s*(?:[¥￥$]|元|USD|RMB)|[0-9]+\\.[0-9]+",
        RegexOption.IGNORE_CASE,
    )
    private val cardTail = Regex("(?:尾号|ending)\\s*[：:]?\\s*([0-9]{4,8})", RegexOption.IGNORE_CASE)

    /** Returns only codes that are safe to relay. */
    fun parse(text: String, fallbackSender: String = ""): VerificationCodeMatch? =
        inspect(text, fallbackSender)?.takeUnless { it.blockedPayment }

    /** Includes blocked matches so callers can record a code-free safety event. */
    fun inspect(text: String, fallbackSender: String = ""): VerificationCodeMatch? {
        val keywords = keyword.findAll(text).toList()
        if (keywords.isEmpty()) return null

        val candidates = buildList {
            compactCandidate.findAll(text).forEach { add(Candidate(it.value, it.range, spaced = false)) }
            spacedDigits.findAll(text).forEach { add(Candidate(it.value.replace(" ", ""), it.range, spaced = true)) }
        }.filter { candidate ->
            val nearest = keywords.minOf { distance(candidate.range, it.range) }
            nearest <= MAX_KEYWORD_DISTANCE && validCandidate(text, candidate, keywords)
        }
        val chosen = candidates.minWithOrNull(compareBy<Candidate>(
            { candidate -> keywords.minOf { distance(candidate.range, it.range) } },
            { it.range.first },
        )) ?: return null

        val rawSender = signatureSender(text) ?: fallbackSender.trim()
        val spacedCode = chosen.value.takeIf { it.length == 6 && it.all(Char::isDigit) }
            ?.let { it.take(3) + " " + it.drop(3) }
        return VerificationCodeMatch(
            code = chosen.value,
            sender = rawSender.replace(chosen.value, "", ignoreCase = true)
                .let { sender -> spacedCode?.let { sender.replace(it, "", ignoreCase = true) } ?: sender }
                .trim(' ', '-', ':', '：', ',', '，'),
            blockedPayment = payment.containsMatchIn(text),
        )
    }

    private fun validCandidate(text: String, candidate: Candidate, keywords: List<MatchResult>): Boolean {
        val range = candidate.range
        if (candidate.spaced) {
            val preceding = keywords.filter { it.range.last < range.first }
                .minByOrNull { range.first - it.range.last } ?: return false
            if (range.first - preceding.range.last > 12 || !connectorOnly(text, preceding.range, range)) return false
        }
        if (amount.findAll(text).any { overlapsOrContains(it.range, range) }) return false
        if (candidate.value.all(Char::isDigit)) {
            if (candidate.value.length == 4 && candidate.value.toIntOrNull() in 1900..2099) return false
            if (longNumber.findAll(text).any { overlapsOrContains(it.range, range) }) return false
            if (dateOrTime.findAll(text).any { overlapsOrContains(it.range, range) }) return false
            val tail = cardTail.findAll(text).firstOrNull { it.groups[1]?.range == range }
            if (tail != null && keywords.none { distance(it.range, range) <= 12 && connectorOnly(text, it.range, range) }) return false
        }
        return true
    }

    private fun signatureSender(text: String): String? {
        val match = signature.find(text) ?: return null
        return (match.groups[1]?.value ?: match.groups[2]?.value)?.trim()?.takeIf(String::isNotEmpty)
    }

    private fun distance(first: IntRange, second: IntRange): Int = when {
        first.last < second.first -> second.first - first.last - 1
        second.last < first.first -> first.first - second.last - 1
        else -> 0
    }

    private fun overlapsOrContains(outer: IntRange, inner: IntRange): Boolean =
        inner.first <= outer.last && inner.last >= outer.first

    private fun connectorOnly(text: String, first: IntRange, second: IntRange): Boolean {
        if (first.last >= second.first) return true
        return text.substring(first.last + 1, second.first).matches(
            Regex("[\\s:：,，=-]*(?:(?:is|为|是)[\\s:：,，=-]*)?", RegexOption.IGNORE_CASE),
        )
    }

    private data class Candidate(val value: String, val range: IntRange, val spaced: Boolean)

    private const val MAX_KEYWORD_DISTANCE = 48
}
