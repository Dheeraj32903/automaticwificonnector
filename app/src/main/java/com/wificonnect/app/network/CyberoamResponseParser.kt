package com.wificonnect.app.network

import com.wificonnect.app.model.LoginResult
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Parser for Cyberoam / Sophos captive portal XML responses.
 *
 * Supported response structures:
 * <requestresponse>
 *     <status>LIVE</status>
 *     <message><![CDATA[...]]></message>
 * </requestresponse>
 *
 * <requestresponse>
 *     <status>LOGIN</status>
 *     <message><![CDATA[...]]></message>
 * </requestresponse>
 *
 * <requestresponse>
 *     <status>CHALLENGE</status>
 *     <state>...</state>
 *     <message><![CDATA[...]]></message>
 * </requestresponse>
 */
object CyberoamResponseParser {

    private const val STATUS_LIVE = "LIVE"
    private const val STATUS_LOGIN = "LOGIN"
    private const val STATUS_CHALLENGE = "CHALLENGE"

    // Keywords that indicate login / device / session limit reached
    private val LIMIT_KEYWORDS = listOf(
        "limit",
        "maximum login",
        "maximum user",
        "login limit",
        "session limit",
        "device limit",
        "exceeded",
        "concurrent",
        "already logged in",
        "simultaneous",
        "quota"
    )

    fun isLogoutSuccessful(responseBody: String): Boolean {
        val lower = responseBody.trim().lowercase()
        if (lower.isEmpty()) return true
        return lower.contains("logout") ||
               lower.contains("logged off") ||
               lower.contains("logged out") ||
               lower.contains("status>login") ||
               lower.contains("successful") ||
               (!lower.contains("error") && !lower.contains("fail"))
    }

    fun parse(xmlString: String, portalUrl: String? = null): LoginResult {
        val trimmed = xmlString.trim()
        if (trimmed.isEmpty()) {
            return LoginResult.UnknownError("Empty response received from authentication server.", portalUrl = portalUrl)
        }

        // Fast path 1: Instant match for LIVE status (bypasses heavy DOM parser for ~0.1ms speed)
        if (trimmed.contains("<status>LIVE</status>", ignoreCase = true) ||
            trimmed.contains("<status><![CDATA[LIVE]]></status>", ignoreCase = true)) {
            val msg = extractCdataOrTag(trimmed, "message") ?: "Connected ✓"
            return LoginResult.Success(msg, portalUrl)
        }

        try {
            val factory = DocumentBuilderFactory.newInstance()
            // Disable external DTDs to protect against XXE
            factory.isNamespaceAware = true
            try {
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            } catch (_: Exception) {}

            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(InputSource(StringReader(trimmed)))
            doc.documentElement.normalize()

            val root = doc.documentElement
            val status = getTagValue(root, "status")?.trim() ?: ""
            val message = getTagValue(root, "message")?.trim() ?: ""
            val state = getTagValue(root, "state")?.trim()

            return when {
                status.equals(STATUS_LIVE, ignoreCase = true) -> {
                    LoginResult.Success(
                        message = if (message.isNotEmpty()) message else "Connected ✓",
                        portalUrl = portalUrl
                    )
                }

                status.equals(STATUS_LOGIN, ignoreCase = true) -> {
                    val lowerMessage = message.lowercase()
                    val isLimitExceeded = LIMIT_KEYWORDS.any { lowerMessage.contains(it) }

                    if (isLimitExceeded) {
                        LoginResult.LimitExceeded(
                            message = "Login limit exceeded.",
                            details = if (message.isNotEmpty()) message else null,
                            portalUrl = portalUrl
                        )
                    } else {
                        LoginResult.InvalidCredentials(
                            message = if (message.isNotEmpty()) message else "Invalid username or password.",
                            portalUrl = portalUrl
                        )
                    }
                }

                status.equals(STATUS_CHALLENGE, ignoreCase = true) -> {
                    LoginResult.ChallengeRequired(
                        message = if (message.isNotEmpty()) message else "Additional authentication required.",
                        state = state,
                        portalUrl = portalUrl
                    )
                }

                else -> {
                    // Fallback in case status is absent or unknown format
                    if (message.isNotEmpty()) {
                        LoginResult.UnknownError(message, portalUrl = portalUrl)
                    } else {
                        LoginResult.UnknownError("Unexpected response format from portal.", portalUrl = portalUrl)
                    }
                }
            }
        } catch (e: Exception) {
            // If XML parsing fails, check if the response is an HTML page (e.g. redirect or portal login form)
            return if (trimmed.contains("<html", ignoreCase = true) || trimmed.contains("<!doctype html", ignoreCase = true)) {
                LoginResult.ChallengeRequired(
                    message = "Portal returned web interface. Additional authentication may be required.",
                    state = null,
                    portalUrl = portalUrl
                )
            } else {
                LoginResult.UnknownError("Failed to parse portal response: ${e.message}", portalUrl = portalUrl)
            }
        }
    }

    private fun getTagValue(element: Element, tagName: String): String? {
        val list = element.getElementsByTagName(tagName)
        if (list != null && list.length > 0) {
            val node = list.item(0)
            return node?.textContent
        }
        return null
    }

    private fun extractCdataOrTag(xml: String, tag: String): String? {
        val options = setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        val cdataPattern = Regex("<$tag>\\s*<!\\[CDATA\\[(.*?)\\]\\]>\\s*</$tag>", options)
        val cdataMatch = cdataPattern.find(xml)
        if (cdataMatch != null) return cdataMatch.groupValues[1].trim()

        val simplePattern = Regex("<$tag>(.*?)</$tag>", options)
        val simpleMatch = simplePattern.find(xml)
        return simpleMatch?.groupValues?.get(1)?.trim()
    }
}
