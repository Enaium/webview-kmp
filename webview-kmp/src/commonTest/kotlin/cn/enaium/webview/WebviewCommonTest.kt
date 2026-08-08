package cn.enaium.webview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebviewCommonTest {

    @Test
    fun testVersion() {
        val version = getWebviewVersion()
        assertTrue(version.versionNumber.isNotBlank())
        assertTrue(version.major >= 0)
        assertTrue(version.minor >= 0)
        assertTrue(version.patch >= 0)
    }
}
