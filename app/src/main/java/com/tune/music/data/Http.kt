package com.tune.music.data

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** The little HTTP the app needs (AcoustID, MusicBrainz, Cover Art Archive). Swappable in tests. */
interface Http {
    /** GET, following redirects. Throws on non-2xx. */
    fun get(url: String): ByteArray

    /** POST an urlencoded form; returns the body even for error statuses. */
    fun postForm(url: String, form: String): String
}

/** Thrown instead of connecting while the network kill switch is on. */
class OfflineException : IOException("web requests are turned off (settings → network kill switch)")

/**
 * The one way out to the internet: every web request goes through [get] or
 * [postForm], which refuse while [blocked] (the network kill switch).
 */
object Net {
    @Volatile
    var http: Http = UrlConnectionHttp

    @Volatile
    var blocked = false

    fun get(url: String): ByteArray {
        if (blocked) throw OfflineException()
        return http.get(url)
    }

    fun postForm(url: String, form: String): String {
        if (blocked) throw OfflineException()
        return http.postForm(url, form)
    }
}

/**
 * Keeps requests to each web service under its published rate limit, across
 * the whole app: callers wait their turn (on an IO thread).
 */
class Throttle(
    private val intervals: Map<String, Long>,
    private val now: () -> Long = System::currentTimeMillis,
    private val sleep: (Long) -> Unit = Thread::sleep,
) {
    private val next = HashMap<String, Long>()

    /** Waits until a request to [host] is allowed, and books its slot. */
    fun acquire(host: String) {
        val interval = intervals.entries.firstOrNull { host == it.key || host.endsWith("." + it.key) }?.value ?: return
        val wait: Long
        synchronized(next) {
            val t = now()
            val slot = maxOf(t, next[host] ?: 0L)
            next[host] = slot + interval
            wait = slot - t
        }
        if (wait > 0) sleep(wait)
    }

    companion object {
        /** AcoustID: 3 requests a second. MusicBrainz: 1 a second. Cover Art Archive: be as polite. */
        val SERVICES = mapOf(
            "api.acoustid.org" to 334L,
            "musicbrainz.org" to 1_000L,
            "coverartarchive.org" to 1_000L,
        )
    }
}

object UrlConnectionHttp : Http {
    private val throttle = Throttle(Throttle.SERVICES)

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

    private fun connection(url: URL): HttpURLConnection {
        throttle.acquire(url.host)
        return (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = false
            // MusicBrainz asks every client to name itself and a contact.
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json, image/*")
        }
    }

    const val USER_AGENT = "Tune/1.0 ( https://github.com/Gabrielcarvfer/tune )"
}
