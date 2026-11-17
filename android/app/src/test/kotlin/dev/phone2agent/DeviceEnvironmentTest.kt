package dev.phone2agent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.TimeZone

class DeviceEnvironmentTest {
    @Test
    fun `timezone id follows the system default`() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
            assertEquals("Asia/Shanghai", DeviceEnvironment.timezoneId)
        } finally {
            TimeZone.setDefault(original)
        }
    }
}
