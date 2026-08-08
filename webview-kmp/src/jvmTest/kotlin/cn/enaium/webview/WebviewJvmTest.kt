package cn.enaium.webview

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class WebviewJvmTest {

    @Test
    fun testVersion() {
        val version = getWebviewVersion()
        assertEquals(0, version.major)
        assertEquals(12, version.minor)
        assertTrue(version.versionNumber.isNotBlank())
    }
}
