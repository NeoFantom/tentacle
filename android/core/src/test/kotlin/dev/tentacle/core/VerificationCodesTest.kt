package dev.tentacle.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VerificationCodesTest {
    @Test
    fun `extracts common Chinese verification messages and signatures`() {
        val cases = listOf(
            "【阿里云】验证码123456，5分钟内有效" to ("123456" to "阿里云"),
            "【腾讯云】您的验证码为：654321，请于5分钟内填写。如非本人操作，请忽略本短信。" to ("654321" to "腾讯云"),
            "阿里云\n【阿里云】验证码123456，5分钟内有效" to ("123456" to "阿里云"),
            "【微信】登录码 839201，请勿告诉他人" to ("839201" to "微信"),
            "确认码AB12CD [QQ]" to ("AB12CD" to "QQ"),
        )
        cases.forEach { (text, expected) ->
            val result = VerificationCodes.parse(text, "fallback")
            assertEquals(expected.first, result?.code, text)
            assertEquals(expected.second, result?.sender, text)
        }
    }

    @Test
    fun `extracts common English formats and nearest candidate`() {
        val cases = mapOf(
            "Your code is 123456" to "123456",
            "G-123456 is your Google verification code" to "123456",
            "Microsoft security code: 482901" to "482901",
            "GitHub verification code: 91A2BC" to "91A2BC",
            "Your Apple ID Code is: 246810. Don't share it with anyone." to "246810",
            "Reference 87654321; your OTP is 441122; ticket 998877" to "441122",
        )
        cases.forEach { (text, code) -> assertEquals(code, VerificationCodes.parse(text, "Messages")?.code, text) }
    }

    @Test
    fun `joins a spaced code only directly after a keyword`() {
        assertEquals("123456", VerificationCodes.parse("验证码 123 456，请填写", "Messages")?.code)
        assertEquals("123456", VerificationCodes.parse("Your code is 123 456", "Messages")?.code)
        assertNull(VerificationCodes.parse("123 456 是你的验证码", "Messages"))
    }

    @Test
    fun `rejects payment codes while allowing login and refund messages`() {
        assertEquals("778899", VerificationCodes.parse("【招商银行】您正在登录手机银行，验证码：778899，尾号1234的卡…")?.code)
        assertNull(VerificationCodes.parse("【招商银行】您正在进行支付，验证码112233，请勿泄露"))
        val blocked = VerificationCodes.inspect("Payment verification code: 112233", "Bank")
        assertEquals("112233", blocked?.code)
        assertTrue(blocked!!.blockedPayment)
        assertEquals("556677", VerificationCodes.parse("退款￥128.00 验证码556677", "Store")?.code)
        assertNull(VerificationCodes.parse("您本次消费￥128.00，验证码 556677", "Bank"))
    }

    @Test
    fun `rejects unrelated and misleading numbers`() {
        val rejected = listOf(
            "订单号 12345678 已发货",
            "验证码将在稍后发送",
            "2026年验证码服务升级",
            "验证码：2026-11-20",
            "验证码：12:30 后失效",
            "验证码 ￥1234 元",
            "code 1234.56",
            "code USD 1234",
            "code USD1234",
            "code RMB123456",
            "code RMB1234",
            "code 123456USD",
            "code 1234USD",
            "code 1234$",
            "code 1234¥",
            "code 1234￥",
            "验证码 13800138000",
            "shipping number 123456",
            "zipcode 12345",
            "pincode 123456",
            "login code unavailable, ending 1234",
            "code use these words 123 456",
        )
        rejected.forEach { assertNull(VerificationCodes.parse(it, "Messages"), it) }
    }

    @Test
    fun `blocks inflected English payment terms`() {
        listOf("payments", "transfers", "withdrawing", "purchases").forEach { action ->
            assertNull(VerificationCodes.parse("Code 123456 for $action", "Bank"), action)
        }
    }

    @Test
    fun `falls back to a sanitized supplied sender`() {
        val result = VerificationCodes.parse("Your code is 123456", "Messages 123456")!!
        assertEquals("Messages", result.sender)
        assertFalse(result.blockedPayment)
    }
}
