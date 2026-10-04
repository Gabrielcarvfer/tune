package com.tune.music.data

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** The little HTTP the app needs (AcoustID, Cover Art Archive). Swappable in tests. */
interface Http {
    /** GET, following redirects. Throws on non-2xx. */
    fun get(url: String): ByteArray

    /** POST an urlencoded form; returns the body even for error statuses. */
    fun postForm(url: String, form: String): String
}

object Net {
    @Volatile
    var http: Http = UrlConnectionHttp
}

object UrlConnectionHttp : Http {
    override fun get(url: String): ByteArray {
        val c = open(url)
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    override fun postForm(url: String, form: String): String {
        val c = connection(URL(url))
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.use { it.write(form.toByteArray()) }
            val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
            return stream?.use { it.readBytes().decodeToString() } ?: throw IOException("HTTP ${c.responseCode}")
        } finally {
            c.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection {
        var target = URL(url)
        // Follow redirects manually: the Cover Art Archive hops between hosts.
        repeat(5) {
            val c = connection(target)
            if (c.responseCode in 300..399) {
                val loc = c.getHeaderField("Location")
                c.disconnect()
                target = URL(target, loc)
            } else {
                return c
            }
        }
        throw IOException("too many redirects")
    }

    private fun connection(url: URL): HttpURLConnection =
        (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", "Tune/1.0 (Android)")
            setRequestProperty("Accept", "application/json, image/*")
        }
}
