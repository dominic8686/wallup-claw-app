package com.wallupclaw.app.util

import java.net.HttpURLConnection
import java.net.URL

/**
 * Shared HTTP client for the token server that attaches the INTERCOM_API_KEY
 * as a Bearer token on every request.
 *
 * Usage:
 *   val client = TokenServerClient(baseUrl = "http://192.168.211.153:8090", apiKey = "...")
 *   val response = client.get("/devices")
 *   val response = client.post("/register", jsonBody)
 */
class TokenServerClient(
    val baseUrl: String,
    var apiKey: String = "",
) {
    /** GET request, returns response body as String. Throws on non-2xx. */
    fun get(path: String): String {
        val conn = URL("$baseUrl$path").openConnection() as HttpURLConnection
        conn.connectTimeout = 5_000
        conn.readTimeout = 10_000
        conn.requestMethod = "GET"
        attachAuth(conn)
        return conn.inputStream.bufferedReader().readText().also { conn.disconnect() }
    }

    /** GET request, returns the HttpURLConnection (caller manages it). */
    fun getConnection(path: String): HttpURLConnection {
        val conn = URL("$baseUrl$path").openConnection() as HttpURLConnection
        conn.connectTimeout = 5_000
        conn.readTimeout = 10_000
        conn.requestMethod = "GET"
        attachAuth(conn)
        return conn
    }

    /** POST JSON, returns response body as String. Throws on non-2xx. */
    fun post(path: String, jsonBody: String): String {
        val conn = URL("$baseUrl$path").openConnection() as HttpURLConnection
        conn.connectTimeout = 5_000
        conn.readTimeout = 10_000
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        attachAuth(conn)
        conn.outputStream.write(jsonBody.toByteArray())
        return conn.inputStream.bufferedReader().readText().also { conn.disconnect() }
    }

    /** POST JSON, returns the HTTP response code. */
    fun postGetCode(path: String, jsonBody: String): Int {
        val conn = URL("$baseUrl$path").openConnection() as HttpURLConnection
        conn.connectTimeout = 5_000
        conn.readTimeout = 10_000
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        attachAuth(conn)
        conn.outputStream.write(jsonBody.toByteArray())
        return conn.responseCode.also { conn.disconnect() }
    }

    /** Long-poll GET (25s timeout for /signals). */
    fun longPollGet(path: String): String {
        val conn = URL("$baseUrl$path").openConnection() as HttpURLConnection
        conn.connectTimeout = 5_000
        conn.readTimeout = 30_000  // 25s long-poll + 5s margin
        conn.requestMethod = "GET"
        attachAuth(conn)
        return conn.inputStream.bufferedReader().readText().also { conn.disconnect() }
    }

    private fun attachAuth(conn: HttpURLConnection) {
        if (apiKey.isNotEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
        }
    }
}
