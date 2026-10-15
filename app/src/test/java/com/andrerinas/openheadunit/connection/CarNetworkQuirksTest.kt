package com.andrerinas.openheadunit.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarNetworkQuirksTest {

    private val saicScript = """
        # comment
        #retry_set_iptables_rule "iptables -t filter -I" "INPUT -p tcp --dport 9999 -j ACCEPT"
        retry_set_iptables_rule "iptables -t filter -I" "INPUT -p icmp --icmp-type echo-request -j REJECT"
        retry_set_iptables_rule "iptables -t filter -I" "INPUT -p udp --dport 58420 -j ACCEPT"
        retry_set_iptables_rule "iptables -t filter -I" "INPUT -p tcp --dport 30517 -j ACCEPT"
        retry_set_iptables_rule "iptables -t filter -I" "INPUT -p tcp --dport 30518 -j ACCEPT"
        retry_set_iptables_rule "iptables -t filter -I" "INPUT -p tcp --dport 6010 -j ACCEPT"
        retry_set_iptables_rule "iptables -t filter -I" "INPUT -p tcp --dport 8080 -j ACCEPT"
        retry_set_iptables_rule "iptables -t filter -I" "INPUT -p udp --dport 67 -j ACCEPT"
        retry_set_iptables_rule "iptables -A" "INPUT -j REJECT"
    """.trimIndent()

    @Test
    fun `parses the SAIC whitelist and the default reject`() {
        val fw = CarNetworkQuirks.parseFirewall(saicScript, "/system/bin/arp_update.sh")
        assertNotNull(fw)
        assertTrue(fw!!.rejectsByDefault)
        assertEquals(listOf(30517, 30518, 6010, 8080), fw.allowedTcp)
    }

    @Test
    fun `commented rules and udp or icmp rules are ignored`() {
        val fw = CarNetworkQuirks.parseFirewall(saicScript, "x")!!
        assertFalse(9999 in fw.allowedTcp)
        assertFalse(58420 in fw.allowedTcp)
        assertFalse(67 in fw.allowedTcp)
    }

    @Test
    fun `no firewall rules gives null`() {
        assertNull(CarNetworkQuirks.parseFirewall("echo hello\nip link set eth0 up", "x"))
    }

    @Test
    fun `restrictive firewall picks 30518 first`() {
        val fw = CarNetworkQuirks.parseFirewall(saicScript, "x")
        val (port, _) = CarNetworkQuirks.pickPort(fw, isSaic = true) { true }
        assertEquals(30518, port)
    }

    @Test
    fun `restrictive firewall skips a port that cannot be bound`() {
        val fw = CarNetworkQuirks.parseFirewall(saicScript, "x")
        val (port, _) = CarNetworkQuirks.pickPort(fw, isSaic = true) { it != 30518 }
        assertEquals(30517, port)
    }

    @Test
    fun `restrictive firewall without a preferred port uses an allowed one`() {
        val fw = CarNetworkQuirks.Firewall("x", true, listOf(4000, 5000))
        val (port, _) = CarNetworkQuirks.pickPort(fw, isSaic = true) { true }
        assertEquals(4000, port)
    }

    @Test
    fun `all allowed candidates busy returns no port`() {
        val fw = CarNetworkQuirks.parseFirewall(saicScript, "x")
        val (port, _) = CarNetworkQuirks.pickPort(fw, isSaic = true) { false }
        assertEquals(0, port)
    }

    @Test
    fun `unreadable SAIC firewall does not select an occupied fallback port`() {
        val (port, _) = CarNetworkQuirks.pickPort(null, isSaic = true) { false }
        assertEquals(0, port)
    }

    @Test
    fun `unreadable script on a SAIC device falls back to 30518`() {
        val (port, _) = CarNetworkQuirks.pickPort(null, isSaic = true) { true }
        assertEquals(CarNetworkQuirks.SAIC_FALLBACK_PORT, port)
    }

    @Test
    fun `other devices keep the standard port`() {
        val (port, _) = CarNetworkQuirks.pickPort(null, isSaic = false) { true }
        assertEquals(CarNetworkQuirks.STANDARD_PORT, port)
    }

    @Test
    fun `a firewall without default reject keeps the standard port`() {
        val fw = CarNetworkQuirks.Firewall("x", false, listOf(80))
        val (port, _) = CarNetworkQuirks.pickPort(fw, isSaic = true) { true }
        assertEquals(CarNetworkQuirks.STANDARD_PORT, port)
    }

    // ---- /system/bin/arp_update.sh as shipped in the firmware (extracted from the OTA payloads) ----

    /** SWI68 (MG4 Trophy R71) and SWI69 (R42) ship the very same script: DROP rules only, no whitelist, no default reject. */
    private val swi68And69Script = """
        #!/system/bin/sh

        echo " " >> /data/iptables.txt
        echo ".... iptables rule setting start ......" >> /data/iptables.txt

        #disable audio prot
        iptables -t filter -I INPUT -p tcp --dport 63012 -j DROP
        iptables -t filter -I INPUT -p tcp --dport 63014 -j DROP

        #disable adb prot
        iptables -t filter -I INPUT -p udp --dport 5555 -j DROP
        iptables -t filter -I INPUT -p tcp --dport 5555 -j DROP
        ip6tables -t filter -I INPUT -p udp --dport 5555 -j DROP
        ip6tables -t filter -I INPUT -p tcp --dport 5555 -j DROP

        #disable upgrade prot
        iptables -t filter -I INPUT -p tcp --dport 7777 -j DROP

        #disable natp prot
        iptables -t filter -I INPUT -p tcp --dport 2011 -j DROP

        #disable carplay prot
        iptables -t filter -I INPUT -p tcp --dport 7000 -j DROP
        iptables -t filter -I INPUT -p upd --dport 5353 -j DROP
        ip6tables -t filter -I INPUT -p tcp --dport 7000 -i !usb0 -j DROP
        ip6tables -t filter -I INPUT -p upd --dport 5353 -i !usb0 -j DROP

        #disable dns masq prot
        iptables -t filter -I INPUT -p tcp --dport 53 -j DROP
        iptables -t filter -I INPUT -p udp --dport 53 -j DROP
        ip6tables -t filter -I INPUT -p tcp --dport 53 -j DROP
        ip6tables -t filter -I INPUT -p udp --dport 53 -j DROP

        #disable IPv6 data packet for ethenet
        ip6tables -t filter -I INPUT -i eth0* -j DROP
        ip6tables -t filter -I FORWARD -i eth0* -j DROP
        ip6tables -t filter -I OUTPUT -o eth0* -j DROP
        ip6tables -I INPUT -i eth0* -m pkttype --pkt-type multicast -j DROP
        ip6tables -I OUTPUT -o eth0* -m pkttype --pkt-type multicast -j DROP
        ip6tables -I INPUT -i eth0* -s ::/0 -d ::/0 -j DROP
        ip6tables -I OUTPUT -o eth0* -s ::/0 -d ::/0 -j DROP

        iptables -t filter -I INPUT -i lo -j ACCEPT

        echo ".... iptables rule setting start ......" >> /data/iptables.txt
    """.trimIndent()

    /** The firewall block of SWI132 (R43) and SWI133 (R36): the same whitelist, ending with a default reject. */
    private val swi132And133Rules = """
        iptables -t filter -P INPUT ACCEPT
        iptables -t filter -P FORWARD ACCEPT
        iptables -t filter -P OUTPUT ACCEPT

        iptables -t filter -I INPUT -i lo -j ACCEPT
        iptables -t filter -I OUTPUT -o lo -j ACCEPT

        retry_set_iptables_rule "iptables -t filter -I" "INPUT -p icmp --icmp-type echo-request -j REJECT"
        retry_set_iptables_rule "iptables -t filter -I" "INPUT -p udp --dport 58420 -j ACCEPT"
        retry_set_iptables_rule "iptables -t filter -I" "INPUT -p tcp --dport 30517 -j ACCEPT"
        retry_set_iptables_rule "iptables -t filter -I" "INPUT -p tcp --dport 30518 -j ACCEPT"
        retry_set_iptables_rule "iptables -t filter -I" "INPUT -p udp --dport 30490 -j ACCEPT"
        retry_set_iptables_rule "iptables -t filter -I" "INPUT -p tcp --dport 6010 -j ACCEPT"
        retry_set_iptables_rule "iptables -t filter -I" "INPUT -p tcp --dport 8080 -j ACCEPT"
        retry_set_iptables_rule "iptables -t filter -I" "INPUT -p udp --dport 67 -j ACCEPT"
        retry_set_iptables_rule "iptables -t filter -I" "INPUT -s 192.168.43.0/24 -d 172.31.0.0/16 -j DROP"
        retry_set_iptables_rule "iptables -I" "INPUT -m state --state RELATED,ESTABLISHED -j ACCEPT"
        retry_set_iptables_rule "iptables -I" "OUTPUT -m state --state RELATED,ESTABLISHED -j ACCEPT"
        retry_set_iptables_rule "iptables -A" "INPUT -j REJECT"
        iptables -I INPUT -m state --state NEW -p tcp -j LOG --log-prefix "TCP connection: "
    """.trimIndent()

    @Test
    fun `SWI68 and SWI69 have no whitelist and no default reject`() {
        assertNull(CarNetworkQuirks.parseFirewall(swi68And69Script, "/system/bin/arp_update.sh"))
    }

    @Test
    fun `SWI68 and SWI69 on a SAIC device fall back to 30518`() {
        val fw = CarNetworkQuirks.parseFirewall(swi68And69Script, "/system/bin/arp_update.sh")
        val (port, _) = CarNetworkQuirks.pickPort(fw, isSaic = true) { true }
        assertEquals(30518, port)
    }

    @Test
    fun `SWI68 and SWI69 never end up on a port their script drops`() {
        val dropped = Regex("""--dport\s+(\d+)\s+-j\s+DROP""").findAll(swi68And69Script).map { it.groupValues[1].toInt() }.toSet()
        val fw = CarNetworkQuirks.parseFirewall(swi68And69Script, "x")
        for (isSaic in listOf(true, false)) {
            val (port, _) = CarNetworkQuirks.pickPort(fw, isSaic) { true }
            assertFalse("port $port is dropped by the script (isSaic=$isSaic)", port in dropped)
        }
    }

    @Test
    fun `SWI132 and SWI133 share the same whitelist and default reject`() {
        val fw = CarNetworkQuirks.parseFirewall(swi132And133Rules, "/system/bin/arp_update.sh")
        assertNotNull(fw)
        assertTrue(fw!!.rejectsByDefault)
        assertEquals(listOf(30517, 30518, 6010, 8080), fw.allowedTcp)
    }

    @Test
    fun `SWI132 and SWI133 pick 30518 whether or not the device is recognised as SAIC`() {
        val fw = CarNetworkQuirks.parseFirewall(swi132And133Rules, "x")
        assertEquals(30518, CarNetworkQuirks.pickPort(fw, isSaic = true) { true }.first)
        assertEquals(30518, CarNetworkQuirks.pickPort(fw, isSaic = false) { true }.first)
    }

    @Test
    fun `SWI132 and SWI133 move to 30517 when 30518 is taken`() {
        val fw = CarNetworkQuirks.parseFirewall(swi132And133Rules, "x")
        assertEquals(30517, CarNetworkQuirks.pickPort(fw, isSaic = true) { it != 30518 }.first)
    }
}
