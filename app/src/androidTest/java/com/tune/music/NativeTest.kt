package com.tune.music

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tune.music.data.Chromaprint
import com.tune.music.data.TagLib
import com.tune.music.support.TestMedia
import com.tune.music.support.TuneTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The C++ side: Chromaprint fingerprints and TagLib tag I/O, through JNI. */
@RunWith(AndroidJUnit4::class)
class NativeTest : TuneTest() {

    override val songs = listOf(TestMedia.ALPHA, TestMedia.DELTA)

    @Test fun fingerprintsAreStableAndTellSongsApart() = runBlocking {
        val a1 = Chromaprint.fingerprint(ctx, uris.getValue("Alpha Song"))
        val a2 = Chromaprint.fingerprint(ctx, uris.getValue("Alpha Song"))
        val d = Chromaprint.fingerprint(ctx, uris.getValue("Delta Tune"))
        assertTrue("compressed base64 Chromaprint", a1.startsWith("AQ") && a1.length > 20)
        assertEquals(a1, a2)
        assertNotEquals(a1, d)
    }

    @Test fun tagLibRoundTripsFieldsAndRemovesEmptyOnes() {
        val copy = File(ctx.cacheDir, "roundtrip.m4a")
        File(TestMedia.pathOf(ctx, uris.getValue("Alpha Song"))!!).copyTo(copy, overwrite = true)
        val values = arrayOfNulls<String>(TagLib.FIELD_COUNT)
        values[TagLib.TITLE] = "Über Ünïcödé ✓"
        values[TagLib.GENRE] = "" // remove
        values[TagLib.TRACK] = "5"
        assertTrue(TagLib.nativeWrite(copy.path, values, null, null))

        val read = TagLib.nativeRead(copy.path)!!
        assertEquals("Über Ünïcödé ✓", read[TagLib.TITLE])
        assertEquals("", read[TagLib.GENRE])
        assertEquals("5", read[TagLib.TRACK])
        assertEquals(TestMedia.ALBUM, read[TagLib.ALBUM]) // untouched
        copy.delete()
    }

    @Test fun tagLibRefusesFilesItCannotParse() {
        // MP4 needs a real atom structure (MPEG is lenient and syncs on anything).
        val junk = File(ctx.cacheDir, "junk.m4a").apply { writeText("not audio at all") }
        assertNull(TagLib.nativeRead(junk.path))
        assertFalse(TagLib.nativeWrite(junk.path, arrayOfNulls(TagLib.FIELD_COUNT), null, null))
        assertNull(TagLib.nativeRead(File(ctx.cacheDir, "missing.flac").path))
        junk.delete()
    }
}
