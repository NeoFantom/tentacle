package dev.tentacle.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class AlertQuestionsTest {
    @TempDir lateinit var tempDir: Path

    @Test
    fun `normalization and matching accept equivalent human answers`() {
        assertEquals("hello world", normalizeAlertAnswer("  ＨＥＬＬＯ　 world！！ "))
        val question = AlertQuestion("en", "Formula?", listOf("H2O", "water"), "chemistry")
        assertTrue(question.matches("  h2o。"))
        assertTrue(question.matches("WATER!"))
        assertFalse(question.matches("CO2"))
    }

    @Test
    fun `bundled bank is large broad unique and self matching`() {
        val requiredDomains = setOf("math", "geography", "physics", "chemistry", "biology", "history", "language")
        listOf("en", "zh").forEach { lang ->
            val questions = AlertQuestions.forLanguage(lang)
            assertTrue(questions.size >= 45, "$lang count=${questions.size}")
            assertEquals(requiredDomains, questions.map { it.domain }.toSet())
            assertEquals(questions.size, questions.map { it.prompt }.toSet().size)
            questions.forEach { question ->
                assertTrue(question.prompt.isNotBlank())
                assertTrue(question.answers.isNotEmpty())
                question.answers.forEach { answer ->
                    assertTrue(normalizeAlertAnswer(answer).isNotEmpty())
                    assertTrue(question.matches(answer), question.prompt)
                }
            }
        }
    }

    @Test
    fun `picker stays stable until second wrong answer and never immediately repeats`() {
        val questions = AlertQuestions.forLanguage("en").take(3)
        val picker = AlertQuestionPicker(questions) { 0 }
        val first = picker.current()
        assertEquals(QuestionPick(first, false), picker.submitWrong())
        val changed = picker.submitWrong()
        assertTrue(changed.changed)
        assertNotEquals(first, changed.question)
        assertEquals(changed.question, picker.current())
        assertFalse(picker.submitWrong().changed)
    }

    @Test
    fun `question store atomically replaces clears reloads and falls back per language`() {
        val file = tempDir.resolve("notify-questions.json").toFile()
        var now = 123L
        val store = AlertQuestionStore(file) { now }
        assertEquals("bundled", store.summary()["source"])
        val supplied = mapOf("questions" to listOf(
            mapOf("lang" to "en", "q" to "What is 2 + 2?", "answers" to listOf("4", "four"), "domain" to "math"),
        ))
        val summary = store.replaceFromJson(supplied)
        assertEquals("computer", summary["source"])
        assertEquals(1, summary["count"])
        assertEquals(123L, summary["updated_ms"])
        assertEquals("What is 2 + 2?", store.questionsFor("en").single().prompt)
        assertTrue(store.questionsFor("zh").size >= 45)

        now = 456L
        val reloaded = AlertQuestionStore(file) { now }
        assertEquals(123L, reloaded.summary()["updated_ms"])
        assertEquals(1, reloaded.questionsFor("en").size)
        assertEquals("bundled", reloaded.replaceFromJson(mapOf("questions" to emptyList<Any>()))["source"])
        assertFalse(file.exists())
    }

    @Test
    fun `question store rejects invalid set without replacing valid data`() {
        val store = AlertQuestionStore(tempDir.resolve("questions.json").toFile())
        val valid = mapOf("questions" to listOf(
            mapOf("lang" to "en", "q" to "Valid?", "answers" to listOf("yes")),
        ))
        store.replaceFromJson(valid)
        val invalidItems = listOf(
            mapOf("lang" to "fr", "q" to "Question?", "answers" to listOf("answer")),
            mapOf("lang" to "en", "q" to "", "answers" to listOf("answer")),
            mapOf("lang" to "en", "q" to "Question?", "answers" to emptyList<String>()),
            mapOf("lang" to "en", "q" to "Question?", "answers" to listOf("！")),
            mapOf("lang" to "en", "q" to "Bad\nquestion", "answers" to listOf("answer")),
            mapOf("lang" to "en", "q" to "Question?", "answers" to listOf("answer"), "domain" to "x".repeat(31)),
            mapOf("lang" to "en", "q" to "Question?", "answers" to listOf("answer"), "domain" to null),
            mapOf("lang" to "en", "q" to "Question?", "answers" to listOf("answer"), "extra" to true),
        )
        invalidItems.forEach { invalid ->
            assertEquals(400, assertThrows(ApiException::class.java) {
                store.replaceFromJson(mapOf("questions" to listOf(invalid)))
            }.status)
            assertEquals("Valid?", store.questionsFor("en").single().prompt)
        }
        assertEquals(400, assertThrows(ApiException::class.java) {
            store.replaceFromJson(mapOf("questions" to List(301) { valid }))
        }.status)
        assertEquals(400, assertThrows(ApiException::class.java) {
            store.replaceFromJson(mapOf("questions" to emptyList<Any>(), "extra" to true))
        }.status)
        assertEquals("Valid?", store.questionsFor("en").single().prompt)
    }
}
