package com.wificonnect.app

import com.wificonnect.app.model.LoginResult
import com.wificonnect.app.network.CyberoamResponseParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests verifying Cyberoam / Sophos captive portal response parsing.
 */
class CyberoamResponseParserTest {

    @Test
    fun testSuccessfulLoginResponse() {
        val xml = """
            <requestresponse>
                <status>LIVE</status>
                <message><![CDATA[You have successfully signed in]]></message>
            </requestresponse>
        """.trimIndent()

        val result = CyberoamResponseParser.parse(xml)
        assertTrue("Expected Success, got: $result", result is LoginResult.Success)
        val success = result as LoginResult.Success
        assertEquals("You have successfully signed in", success.message)
    }

    @Test
    fun testSuccessfulLoginDefaultMessage() {
        val xml = """
            <requestresponse>
                <status>LIVE</status>
                <message></message>
            </requestresponse>
        """.trimIndent()

        val result = CyberoamResponseParser.parse(xml)
        assertTrue(result is LoginResult.Success)
        assertEquals("Connected ✓", (result as LoginResult.Success).message)
    }

    @Test
    fun testInvalidCredentialsResponse() {
        val xml = """
            <requestresponse>
                <status>LOGIN</status>
                <message><![CDATA[The system could not log you on. Make sure your password is correct]]></message>
            </requestresponse>
        """.trimIndent()

        val result = CyberoamResponseParser.parse(xml)
        assertTrue("Expected InvalidCredentials, got: $result", result is LoginResult.InvalidCredentials)
        val failure = result as LoginResult.InvalidCredentials
        assertTrue(failure.message.contains("password is correct"))
    }

    @Test
    fun testLoginLimitExceededResponse() {
        val xml = """
            <requestresponse>
                <status>LOGIN</status>
                <message><![CDATA[You have reached Maximum Login Limit for your account]]></message>
            </requestresponse>
        """.trimIndent()

        val result = CyberoamResponseParser.parse(xml)
        assertTrue("Expected LimitExceeded, got: $result", result is LoginResult.LimitExceeded)
        val limit = result as LoginResult.LimitExceeded
        assertEquals("Login limit exceeded.", limit.message)
        assertNotNull(limit.details)
        assertTrue(limit.details!!.contains("Maximum Login Limit"))
    }

    @Test
    fun testConcurrentLoginExceededResponse() {
        val xml = """
            <requestresponse>
                <status>LOGIN</status>
                <message><![CDATA[User quota or concurrent session limit exceeded.]]></message>
            </requestresponse>
        """.trimIndent()

        val result = CyberoamResponseParser.parse(xml)
        assertTrue(result is LoginResult.LimitExceeded)
    }

    @Test
    fun testChallengeResponse() {
        val xml = """
            <requestresponse>
                <status>CHALLENGE</status>
                <state>chal_token_88921</state>
                <message><![CDATA[Please complete OTP verification on the portal.]]></message>
            </requestresponse>
        """.trimIndent()

        val portalUrl = "http://172.16.1.1:8090"
        val result = CyberoamResponseParser.parse(xml, portalUrl)
        assertTrue("Expected ChallengeRequired, got: $result", result is LoginResult.ChallengeRequired)
        val challenge = result as LoginResult.ChallengeRequired
        assertEquals("chal_token_88921", challenge.state)
        assertEquals(portalUrl, challenge.portalUrl)
        assertTrue(challenge.message.contains("OTP verification"))
    }

    @Test
    fun testHtmlWebResponseFallbackToChallenge() {
        val html = """
            <!DOCTYPE html>
            <html>
                <head><title>Sign in to access this network</title></head>
                <body><div id="credentials">Login Required</div></body>
            </html>
        """.trimIndent()

        val result = CyberoamResponseParser.parse(html, "http://172.16.1.1:8090")
        assertTrue("Expected Challenge/Web fallback for HTML response", result is LoginResult.ChallengeRequired)
    }

    @Test
    fun testMalformedXmlResponse() {
        val malformed = "<requestresponse><status>LIVE</unclosed>"
        val result = CyberoamResponseParser.parse(malformed)
        assertTrue("Expected UnknownError on malformed XML", result is LoginResult.UnknownError)
    }

    @Test
    fun testEmptyResponse() {
        val empty = "   "
        val result = CyberoamResponseParser.parse(empty)
        assertTrue("Expected UnknownError on empty response", result is LoginResult.UnknownError)
    }
}
