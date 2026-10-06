package com.music.tune.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelsTest {
    @Test fun sortKeyIgnoresLeadingThe() {
        assertEquals("ultimate collection", sortKey("The Ultimate Collection"))
        assertEquals("theremin", sortKey("Theremin"))
    }

    @Test fun jumpGroupBucketsLettersAndOthers() {
        assertEquals('u', jumpGroup("The Ultimate Collection"))
        assertEquals('a', jumpGroup("airwalk"))
        assertEquals('#', jumpGroup("10cc"))
        assertEquals('#', jumpGroup("Ólafur Arnalds"))
        assertEquals('#', jumpGroup(""))
    }

    @Test fun formatsDurations() {
        assertEquals("0:00", formatDuration(0))
        assertEquals("2:45", formatDuration(165_400))
        assertEquals("1:01:01", formatDuration(3_661_000))
        assertEquals("0:00", formatDuration(-5))
    }
}
