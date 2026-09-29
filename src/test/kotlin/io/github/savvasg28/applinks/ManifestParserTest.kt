package io.github.savvasg28.applinks

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private fun manifest(body: String) =
    """
    <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.app">
      <application>$body</application>
    </manifest>
    """.trimIndent().toByteArray()

class ManifestParserTest {
    @Test
    fun `collects hosts from autoVerify https filters only`() {
        val xml =
            manifest(
                """
            <activity android:name=".Main">
              <intent-filter android:autoVerify="true">
                <action android:name="android.intent.action.VIEW"/>
                <category android:name="android.intent.category.DEFAULT"/>
                <category android:name="android.intent.category.BROWSABLE"/>
                <data android:scheme="https" android:host="example.com"/>
                <data android:host="app.example.com"/>
              </intent-filter>
              <intent-filter>
                <action android:name="android.intent.action.VIEW"/>
                <data android:scheme="https" android:host="not-verified.example.com"/>
              </intent-filter>
              <intent-filter android:autoVerify="true">
                <action android:name="android.intent.action.VIEW"/>
                <data android:scheme="myapp" android:host="custom-scheme"/>
              </intent-filter>
            </activity>
            <activity android:name=".Other">
              <intent-filter android:autoVerify="true">
                <action android:name="android.intent.action.VIEW"/>
                <data android:scheme="http" android:host="*.example.com"/>
              </intent-filter>
            </activity>
            """,
            )
        val hosts = ManifestParser.autoVerifyHosts(ManifestParser.linkFilters(xml))
        assertEquals(setOf("example.com", "app.example.com"), hosts.concrete)
        assertEquals(setOf("*.example.com"), hosts.wildcards)
    }

    @Test
    fun `ignores autoVerify filters without a VIEW action`() {
        val xml =
            manifest(
                """
            <activity android:name=".Main">
              <intent-filter android:autoVerify="true">
                <action android:name="android.intent.action.SEND"/>
                <data android:scheme="https" android:host="example.com"/>
              </intent-filter>
            </activity>
            """,
            )
        assertTrue(ManifestParser.autoVerifyHosts(ManifestParser.linkFilters(xml)).isEmpty)
    }
}

class LinkFilterTest {
    @Test
    fun `reports what stops verification and keeps unverified filters separate`() {
        val xml =
            manifest(
                """
            <activity android:name=".Main">
              <intent-filter android:autoVerify="true">
                <action android:name="android.intent.action.VIEW"/>
                <category android:name="android.intent.category.DEFAULT"/>
                <data android:scheme="http" android:host="example.com"/>
              </intent-filter>
              <intent-filter>
                <action android:name="android.intent.action.VIEW"/>
                <category android:name="android.intent.category.DEFAULT"/>
                <category android:name="android.intent.category.BROWSABLE"/>
                <data android:scheme="https" android:host="link.tink.com"/>
              </intent-filter>
            </activity>
            """,
            )
        val filters = ManifestParser.linkFilters(xml)
        assertEquals(2, filters.size)
        val verified = filters.first { it.isVerifiable }
        assertEquals(listOf("missing category BROWSABLE", "no https scheme, only http"), verified.verificationProblems())
        val tink = filters.first { !it.autoVerify }
        assertEquals(setOf("link.tink.com"), tink.hosts)
        assertEquals(emptyList<String>(), tink.verificationProblems())
        assertEquals(setOf("example.com"), ManifestParser.autoVerifyHosts(filters).concrete)
    }
}
