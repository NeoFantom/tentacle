package dev.tentacle.core

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Atomic app-private storage for the optional question set supplied by the paired computer. */
class AlertQuestionStore(
    private val file: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var loaded = false
    private var custom: List<AlertQuestion> = emptyList()
    private var updatedMs: Long? = null

    @Synchronized fun questionsFor(lang: String): List<AlertQuestion> {
        load()
        return custom.filter { it.lang == lang }.ifEmpty { AlertQuestions.forLanguage(lang) }
    }

    @Synchronized fun summary(): Map<String, Any> {
        load()
        val questions = custom.ifEmpty { AlertQuestions.bundled }
        return linkedMapOf<String, Any>(
            "source" to if (custom.isEmpty()) "bundled" else "computer",
            "count" to questions.size,
            "counts" to linkedMapOf(
                "en" to questions.count { it.lang == "en" },
                "zh" to questions.count { it.lang == "zh" },
            ),
        ).apply { if (custom.isNotEmpty()) updatedMs?.let { put("updated_ms", it) } }
    }

    /** Validates the whole body before replacing anything. */
    @Synchronized fun replaceFromJson(value: Any?): Map<String, Any> {
        val root = value as? Map<*, *>
            ?: throw ApiException(400, "bad_request", "Expected a JSON object")
        if (root.keys != setOf("questions")) {
            throw ApiException(400, "bad_request", "Only the questions field is allowed")
        }
        val raw = root["questions"] as? List<*>
            ?: throw ApiException(400, "bad_request", "Provide a questions list")
        if (raw.size > MAX_QUESTIONS) {
            throw ApiException(400, "bad_request", "Provide at most 300 questions")
        }
        val validated = raw.mapIndexed { index, item -> parseQuestion(item, index) }
        if (validated.isEmpty()) {
            clear()
            return summary()
        }
        val timestamp = clock()
        val stored = linkedMapOf(
            "questions" to validated.map { it.toJson() },
            "updated_ms" to timestamp,
        )
        writeAtomically(Json.stringify(stored).toByteArray(Charsets.UTF_8))
        custom = validated
        updatedMs = timestamp
        loaded = true
        return summary()
    }

    @Synchronized fun clear(): Map<String, Any> {
        runCatching { Files.deleteIfExists(file.toPath()) }
            .getOrElse { throw ApiException(500, "internal", "Could not clear alert questions") }
        custom = emptyList()
        updatedMs = null
        loaded = true
        return summary()
    }

    private fun load() {
        if (loaded) return
        loaded = true
        if (!file.isFile) return
        try {
            val root = Json.parse(file.readText(Charsets.UTF_8)) as? Map<*, *> ?: return
            val raw = root["questions"] as? List<*> ?: return
            custom = raw.mapIndexed { index, item -> parseQuestion(item, index) }
            updatedMs = (root["updated_ms"] as? Number)?.toLong() ?: file.lastModified()
            if (custom.isEmpty()) updatedMs = null
        } catch (_: Exception) {
            // A corrupt private file is ignored; a later PUT replaces it atomically.
            custom = emptyList()
            updatedMs = null
        }
    }

    private fun parseQuestion(value: Any?, index: Int): AlertQuestion {
        val item = value as? Map<*, *>
            ?: invalid(index, "must be an object")
        if (!item.keys.all { it in QUESTION_FIELDS }) invalid(index, "contains an unknown field")
        val lang = item["lang"] as? String ?: invalid(index, "lang must be a string")
        if (lang !in setOf("en", "zh")) invalid(index, "lang must be en or zh")
        val prompt = item["q"] as? String ?: invalid(index, "q must be a string")
        validateText(prompt, 1, MAX_PROMPT_CHARS, index, "q")
        val rawAnswers = item["answers"] as? List<*> ?: invalid(index, "answers must be a list")
        if (rawAnswers.size !in 1..MAX_ANSWERS) invalid(index, "answers must contain 1 to 10 items")
        val answers = rawAnswers.map { answer ->
            val text = answer as? String ?: invalid(index, "answers must contain strings")
            validateText(text, 1, MAX_ANSWER_CHARS, index, "answer")
            if (normalizeAlertAnswer(text).isEmpty()) invalid(index, "answer must not be empty after normalization")
            text
        }
        val domain = when (val raw = item["domain"]) {
            null -> if (item.containsKey("domain")) invalid(index, "domain must be a string") else ""
            is String -> raw.also {
                if (it.codePointCount(0, it.length) > MAX_DOMAIN_CHARS || it.hasControl()) {
                    invalid(index, "domain must be at most 30 characters without controls")
                }
            }
            else -> invalid(index, "domain must be a string")
        }
        return AlertQuestion(lang, prompt, answers, domain)
    }

    private fun validateText(text: String, minimum: Int, maximum: Int, index: Int, field: String) {
        val count = text.codePointCount(0, text.length)
        if (count !in minimum..maximum || text.hasControl()) {
            invalid(index, "$field must be $minimum to $maximum characters without controls")
        }
    }

    private fun writeAtomically(bytes: ByteArray) {
        try {
            val parent = file.absoluteFile.parentFile
            parent?.mkdirs()
            val temporary = File(parent, ".${file.name}.tmp")
            temporary.writeBytes(bytes)
            try {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (_: Exception) {
            throw ApiException(500, "internal", "Could not store alert questions")
        }
    }

    private fun AlertQuestion.toJson(): Map<String, Any> = linkedMapOf(
        "lang" to lang,
        "q" to prompt,
        "answers" to answers,
    ).apply { if (domain.isNotEmpty()) put("domain", domain) }

    private fun String.hasControl(): Boolean = codePoints().anyMatch(Character::isISOControl)

    private fun invalid(index: Int, message: String): Nothing =
        throw ApiException(400, "bad_request", "Question ${index + 1} $message")

    companion object {
        const val MAX_BODY_BYTES = 64 * 1024
        const val MAX_QUESTIONS = 300
        const val MAX_PROMPT_CHARS = 200
        const val MAX_ANSWERS = 10
        const val MAX_ANSWER_CHARS = 60
        const val MAX_DOMAIN_CHARS = 30
        private val QUESTION_FIELDS = setOf("lang", "q", "answers", "domain")
    }
}
