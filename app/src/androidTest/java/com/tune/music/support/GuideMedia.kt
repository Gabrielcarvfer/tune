package com.tune.music.support

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import java.io.ByteArrayOutputStream

/**
 * A small made-up collection for the README's screenshots: invented artists
 * and albums with drawn covers, so the guide shows no real (copyrighted) music.
 */
object GuideMedia {
    const val MESSY_ARTIST = "Northern Static"
    const val MESSY_ALBUM = "paper satellites"

    // How the release is known online, with the real track titles.
    const val RELEASE = "Paper Satellites"
    val RELEASE_TRACKS = listOf("Signal Fires", "Weather Balloon", "Long Exposure")

    val SONGS: List<TestMedia.Spec> by lazy {
        fun album(artist: String, album: String, year: String, genre: String, art: ByteArray?, vararg titles: String) =
            titles.mapIndexed { i, t ->
                TestMedia.Spec(t, artist, album, i + 1, 6, 220.0 + 37.0 * (t.hashCode() and 0xff) / 16,
                    genre = genre, year = year, art = art)
            }
        album("Mira Vale", "Slow Orbit", "2019", "Dream Pop", cover(0xFF1B2A6B, 0xFF7A3FA0, 0), "Gravity Well", "Low Tide Hymn", "Satellite Hearts") +
            album("Copper Lines", "Night Ferry", "2016", "Indie", cover(0xFF0E5E6F, 0xFF10203A, 1), "Harbour Lights", "Last Crossing") +
            album("The Quiet Hours", "Amber Rooms", "2021", "Folk", cover(0xFFF2A541, 0xFFC0392B, 2), "Window Seat", "Kettle Song") +
            album("Echo Parade", "Neon Garden", "2022", "Synthpop", cover(0xFFD6247A, 0xFF2D1B69, 3), "Midnight Bloom", "Glass Avenue") +
            album("Saltwater Radio", "Coastline", "2014", "Ambient", cover(0xFF2E8B57, 0xFF0B4F6C, 4), "Long Way North") +
            // Badly tagged rips, as they often come: no cover, no year, placeholder titles.
            album(MESSY_ARTIST, MESSY_ALBUM, "", "", null, "track 1", "track 2", "track 3")
    }

    /** A gradient square with a simple motif. */
    fun cover(from: Long, to: Long, motif: Int, size: Int = 600): ByteArray {
        val s = size.toFloat()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, s, s, from.toInt(), to.toInt(), Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, s, s, p)
        p.shader = null
        p.color = Color.WHITE
        p.style = Paint.Style.STROKE
        p.strokeWidth = s / 60
        when (motif % 5) {
            0 -> for (r in 1..6) {
                p.alpha = 200 - r * 25
                cv.drawCircle(s * 0.62f, s * 0.4f, r * s / 14, p)
            }
            1 -> for (i in 0..8) {
                p.alpha = 60 + i * 18
                val y = s * (0.45f + i * 0.06f)
                cv.drawLine(s * 0.1f, y, s * 0.9f, y, p)
            }
            2 -> {
                p.style = Paint.Style.FILL
                p.alpha = 220
                cv.drawCircle(s * 0.5f, s * 0.55f, s * 0.22f, p)
                p.alpha = 255
                p.color = to.toInt()
                cv.drawRect(0f, s * 0.62f, s, s, p)
            }
            3 -> for (x in 0..5) for (y in 0..5) {
                p.style = Paint.Style.FILL
                p.alpha = 70 + 25 * ((x + y) % 7)
                cv.drawCircle(s * (0.17f + x * 0.133f), s * (0.17f + y * 0.133f), s / 40, p)
            }
            else -> for (i in -6..6) {
                p.alpha = 110
                val x = s * (0.5f + i * 0.12f)
                cv.drawLine(x, s, x + s * 0.6f, 0f, p)
            }
        }
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()
    }
}
