package com.restaurant.iptv.epg
import org.junit.Assert.*
import org.junit.Test
class XmlTvTimestampsTest {
    @Test fun parsesOffsetsAndRejectsMissingValues() {
        val parser = XmlTvTimestamps()
        assertEquals(parser.parse("20261027120000 +0000"), parser.parse("20261027070000 -0500"))
        assertEquals(0L, parser.parse(null))
        assertEquals(0L, parser.parse("bad"))
    }
}
