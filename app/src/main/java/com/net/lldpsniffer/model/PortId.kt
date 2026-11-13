package com.net.lldpsniffer.model

/**
 * A switch-port identifier normalized the way netutils' interface utilities do it: the
 * type prefix is canonicalized (so CDP's abbreviated "Te1/0/40" and LLDP's expanded
 * "TenGigabitEthernet1/0/40" are recognized as the same port) and the number portion is
 * parsed into integer components (line card/module/port, optionally with subinterface).
 *
 * Modeled on networktocode/netutils `split_interface()` + `BASE_INTERFACES`: the prefix is
 * everything before the first digit/separator, and matching is done against an explicit
 * case-sensitive map rather than fuzzy regex, to avoid false positives (netutils calls out
 * `Po` = Port-channel vs `po` = POS as the classic trap).
 */
data class PortId(val raw: String) {

    /** Canonical interface type, e.g. "TenGigabitEthernet". Unknown prefixes are kept as-is. */
    val type: String

    /** Parsed number components, e.g. [1, 0, 40] for "Te1/0/40". Empty if unparseable. */
    val numbers: List<Int>

    /**
     * Identity key: two PortIds with the same key refer to the same physical port regardless
     * of abbreviation. Unknown prefixes or unparseable numbers fall back to the raw string so
     * unrelated ports are never falsely considered equal.
     */
    val key: String

    init {
        val trimmed = raw.trim()
        // netutils split_interface: head = name with trailing "/0-9.: " stripped
        val head = trimmed.reversed().dropWhile { it == '/' || it == '.' || it == ':' || it == ' ' || it.isDigit() }.reversed()
        val tail = trimmed.substring(head.length).trim()
        type = BASE_INTERFACES[head] ?: head
        numbers = if (tail.isEmpty()) emptyList() else parseNumbers(tail)
        key = if (numbers.isEmpty() || !BASE_INTERFACES.containsKey(head)) trimmed else "$type:${numbers.joinToString("/")}"
    }

    companion object {
        private fun parseNumbers(tail: String): List<Int> =
            tail.split('/', '.', ':').mapNotNull { it.toIntOrNull() }

        /**
         * Subset of netutils' BASE_INTERFACES map (abbreviation -> canonical name), covering
         * the ethernet families switches actually advertise over LLDP/CDP. Case-sensitive on
         * purpose, same as netutils.
         */
        private val BASE_INTERFACES: Map<String, String> = buildMap {
            fun put(vararg aliases: String, canonical: String) {
                put(canonical, canonical)
                for (a in aliases) put(a, canonical)
            }
            put("Ethernet", "Eth", "Et", "eth", "et", canonical = "Ethernet")
            put("FastEthernet", "FastEth", "FastE", "Fast", "Fas", "FE", "Fa", "fa", canonical = "FastEthernet")
            put("GigabitEthernet", "GigEthernet", "GigEth", "GigE", "Gig", "GE", "Ge", "ge", "Gi", "gi", canonical = "GigabitEthernet")
            put("Ten-GigabitEthernet", "TenGigEthernet", "TenGigEth", "TenGigE", "TenGig", "TeGig", "Ten", "T", "Te", "te", "XGE", canonical = "TenGigabitEthernet")
            put("TwentyFiveGigabitEthernet", "TwentyFiveGigEthernet", "TwentyFiveGigEth", "TwentyFiveGigE", "TwentyFiveGig", "TF", "Tf", "tf", "Twe", "Tw", canonical = "TwentyFiveGigE")
            put("FortyGigabitEthernet", "FortyGigEthernet", "FortyGigEth", "FortyGigE", "FortyGig", "FGE", "FO", "Fo", canonical = "FortyGigabitEthernet")
            put("FiftyGigabitEthernet", "FiftyGigEthernet", "FiftyGigEth", "FiftyGigE", "FI", "Fi", "fi", canonical = "FiftyGigabitEthernet")
            put("HundredGigabitEthernet", "HundredGigEthernet", "HundredGigEth", "HundredGigE", "HundredGig", "Hu", canonical = "HundredGigabitEthernet")
            put("TwoHundredGigabitEthernet", "TwoHundredGigEthernet", "TwoHundredGigEth", "TwoHundredGigE", "TwoHundredGig", "TH", "Th", "th", canonical = "TwoHundredGigabitEthernet")
            put("FourHundredGigabitEthernet", "FourHundredGigEthernet", "FourHundredGigEth", "FourHundredGigE", "FourHundredGig", "F", "f", canonical = "FourHundredGigabitEthernet")
            put("TwoGigabitEthernet", "TwoGig", canonical = "TwoGigabitEthernet")
            put("Port-channel", "Po", "po", "Port-channel", canonical = "Port-channel")
            put("Vlan-interface", "Vl", "Vlan", canonical = "Vlan-interface")
            put("Loopback", "Lo", canonical = "Loopback")
            put("Mgmt-port", "Mgmt", canonical = "Mgmt-port")
        }
    }
}
