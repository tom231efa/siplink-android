package com.sky.siplink.core.logic

import com.sky.siplink.core.logic.LineAdapt.Probe
import com.sky.siplink.core.logic.LineAdapt.Record
import com.sky.siplink.core.model.SipTransport.TCP
import com.sky.siplink.core.model.SipTransport.TLS
import com.sky.siplink.core.model.SipTransport.UDP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LineAdaptTest {
    private val day = 24 * 60 * 60_000L
    private val blocked = Probe(udp = false, udpLarge = null, tcp = true, tls = true)

    @Test
    fun `records survive being stored`() {
        val record = Record(TCP, 300, setOf(TLS), 123, 2, listOf(5, 6), 99, MediaAdapt.Ice.ON, 456)
        assertEquals(record, LineAdapt.decode(LineAdapt.encode(record)))
        assertEquals(Record(), LineAdapt.decode(LineAdapt.encode(Record())))
        assertNull(LineAdapt.decode("TCP|300"))
        assertNull(LineAdapt.decode(null))
        assertTrue(Record().isPlain)
        assertFalse(Record(transport = TCP).isPlain)
        assertFalse(Record(ice = MediaAdapt.Ice.REFUSED).isPlain)
    }

    @Test
    fun `only a missing answer points at the network`() {
        assertTrue(LineAdapt.isTransportFailure(0))
        assertTrue(LineAdapt.isTransportFailure(408))
        assertTrue(LineAdapt.isTransportFailure(503))
        assertFalse(LineAdapt.isTransportFailure(401))
        assertFalse(LineAdapt.isTransportFailure(403))
        assertFalse(LineAdapt.isTransportFailure(500))
    }

    @Test
    fun `udp that does not get through is replaced by tcp, then tls`() {
        assertEquals(TCP, LineAdapt.nextTransport(UDP, UDP, emptySet(), blocked))
        assertEquals(TLS, LineAdapt.nextTransport(UDP, TCP, setOf(TCP), blocked))
        assertEquals(TLS, LineAdapt.nextTransport(UDP, UDP, setOf(TCP), blocked))
        assertNull(LineAdapt.nextTransport(UDP, TLS, setOf(TCP, TLS), blocked))
    }

    @Test
    fun `only what the probe found open is tried`() {
        assertNull(LineAdapt.nextTransport(UDP, UDP, emptySet(), blocked.copy(tcp = false, tls = null)))
        assertEquals(TLS, LineAdapt.nextTransport(UDP, UDP, emptySet(), blocked.copy(tcp = false)))
        assertNull(LineAdapt.nextTransport(UDP, TCP, setOf(TCP), blocked.copy(tls = false)))
    }

    @Test
    fun `a line whose udp answers is left alone unless large packets are lost`() {
        val open = Probe(udp = true, udpLarge = true, tcp = true, tls = null)
        assertNull(LineAdapt.nextTransport(UDP, UDP, emptySet(), open))
        assertEquals(TCP, LineAdapt.nextTransport(UDP, UDP, emptySet(), open.copy(udpLarge = false), udpUnfit = true))
    }

    @Test
    fun `an account set to tcp or tls is never changed`() {
        assertNull(LineAdapt.nextTransport(TCP, TCP, emptySet(), blocked))
        assertNull(LineAdapt.nextTransport(TLS, TLS, emptySet(), blocked))
    }

    @Test
    fun `udp is taken back once small and large requests get through`() {
        assertTrue(LineAdapt.udpBack(Probe(udp = true, udpLarge = true, tcp = true, tls = null)))
        assertTrue(LineAdapt.udpBack(Probe(udp = true, udpLarge = null, tcp = true, tls = null)))
        assertFalse(LineAdapt.udpBack(Probe(udp = true, udpLarge = false, tcp = true, tls = null)))
        assertFalse(LineAdapt.udpBack(blocked))
    }

    @Test
    fun `retests come later each time going back did not hold`() {
        assertEquals(day, LineAdapt.retestDelay(0))
        assertEquals(2 * day, LineAdapt.retestDelay(1))
        assertEquals(7 * day, LineAdapt.retestDelay(3))
        assertEquals(7 * day, LineAdapt.retestDelay(30))
    }

    @Test
    fun `the interval is shortened after the nat forgot the line twice in a day`() {
        val once = LineAdapt.bindingLost(Record(), 3600, 1_000)
        assertNull(once.expires)
        val twice = LineAdapt.bindingLost(once, 3600, 2_000)
        assertEquals(LineAdapt.SHORT_EXPIRES, twice.expires)
        // Two losses more than a day apart are no pattern.
        val apart = LineAdapt.bindingLost(once, 3600, 1_000 + day + 1)
        assertNull(apart.expires)
        // An account that registers often anyway keeps its own interval.
        assertNull(LineAdapt.bindingLost(once, 120, 2_000).expires)
    }

    @Test
    fun `a nat that forgets the line at the short interval as well leaves udp hopeless`() {
        var record = Record()
        for (at in listOf(1_000L, 2_000L)) record = LineAdapt.bindingLost(record, 3600, at)
        assertFalse(LineAdapt.bindingHopeless(record, 3600))
        record = LineAdapt.bindingLost(record, 3600, 3_000)
        assertFalse(LineAdapt.bindingHopeless(record, 3600))
        record = LineAdapt.bindingLost(record, 3600, 4_000)
        assertTrue(LineAdapt.bindingHopeless(record, 3600))
        val open = Probe(udp = true, udpLarge = true, tcp = true, tls = null)
        assertEquals(TCP, LineAdapt.nextTransport(UDP, UDP, emptySet(), open, udpUnfit = true))
        // An account that registers this often by itself: two losses are enough.
        val often = LineAdapt.bindingLost(LineAdapt.bindingLost(Record(), 120, 1_000), 120, 2_000)
        assertTrue(LineAdapt.bindingHopeless(often, 120))
    }

    @Test
    fun `a day without a loss gives the account its interval back`() {
        val short = Record(expires = 300, lost = listOf(1_000, 2_000))
        assertEquals(300, LineAdapt.bindingSteady(short, 2_000 + day - 1).expires)
        val back = LineAdapt.bindingSteady(short, 2_000 + day)
        assertNull(back.expires)
        assertTrue(back.lost.isEmpty())
    }

    @Test
    fun `old records and networks beyond the limit are forgotten`() {
        val now = 100 * day
        val records = HashMap<String, Record>()
        records[LineAdapt.key("a", "old")] = Record(transport = TCP, usedAt = now - 31 * day)
        repeat(LineAdapt.MAX_NETWORKS + 2) { i ->
            records[LineAdapt.key("a", "net$i")] = Record(transport = TCP, usedAt = now - i * 1000L)
            records[LineAdapt.key("b", "net$i")] = Record(transport = TCP, usedAt = now - i * 1000L)
        }
        val stale = LineAdapt.stale(records, now)
        assertTrue(LineAdapt.key("a", "old") in stale)
        assertTrue(LineAdapt.key("a", "net${LineAdapt.MAX_NETWORKS}") in stale)
        assertTrue(LineAdapt.key("b", "net${LineAdapt.MAX_NETWORKS + 1}") in stale)
        assertFalse(LineAdapt.key("b", "net0") in stale)
        assertEquals(1 + 2 * 2, stale.size)
    }

    @Test
    fun `keys split into account and network`() {
        val key = LineAdapt.key("sip:1001@pbx", "0123456789abcdef")
        assertEquals("sip:1001@pbx", LineAdapt.accountOf(key))
        assertEquals("0123456789abcdef", LineAdapt.networkOf(key))
        assertEquals("TCP（自动）", LineAdapt.label(TCP))
    }
}
