package io.github.iacoposb.adodash.api

/**
 * HTML character references as the characters they stand for: named ("&ugrave;"), decimal
 * ("&#249;") and hex ("&#xF9;"). Some titles reach Azure DevOps encoded (imports, mail, other
 * tools) and its web pages show them decoded, so the app does too.
 *
 * One pass only: "&amp;lt;" becomes "&lt;", never "<". Only complete references with their ";"
 * are decoded, and unknown names are left as they are, so "AT&T" or "R&D; tests" stay intact.
 */
object HtmlEntities {
    private val REFERENCE = Regex("&(#[xX][0-9a-fA-F]{1,6}|#[0-9]{1,7}|[a-zA-Z][a-zA-Z0-9]{1,31});")

    /** Latin-1's names, U+00A0 to U+00FF in order. */
    private val LATIN1 = (
        "nbsp iexcl cent pound curren yen brvbar sect uml copy ordf laquo not shy reg macr " +
            "deg plusmn sup2 sup3 acute micro para middot cedil sup1 ordm raquo frac14 frac12 frac34 iquest " +
            "Agrave Aacute Acirc Atilde Auml Aring AElig Ccedil Egrave Eacute Ecirc Euml Igrave Iacute Icirc Iuml " +
            "ETH Ntilde Ograve Oacute Ocirc Otilde Ouml times Oslash Ugrave Uacute Ucirc Uuml Yacute THORN szlig " +
            "agrave aacute acirc atilde auml aring aelig ccedil egrave eacute ecirc euml igrave iacute icirc iuml " +
            "eth ntilde ograve oacute ocirc otilde ouml divide oslash ugrave uacute ucirc uuml yacute thorn yuml"
        ).split(' ')

    private val NAMED: Map<String, Int> = LATIN1.mapIndexed { i, name -> name to 0xA0 + i }.toMap() + mapOf(
        "quot" to 0x22, "amp" to 0x26, "apos" to 0x27, "lt" to 0x3C, "gt" to 0x3E,
        "OElig" to 0x152, "oelig" to 0x153, "Scaron" to 0x160, "scaron" to 0x161, "Yuml" to 0x178, "fnof" to 0x192,
        "circ" to 0x2C6, "tilde" to 0x2DC,
        "ensp" to 0x2002, "emsp" to 0x2003, "thinsp" to 0x2009, "zwnj" to 0x200C, "zwj" to 0x200D,
        "lrm" to 0x200E, "rlm" to 0x200F, "ndash" to 0x2013, "mdash" to 0x2014,
        "lsquo" to 0x2018, "rsquo" to 0x2019, "sbquo" to 0x201A, "ldquo" to 0x201C, "rdquo" to 0x201D, "bdquo" to 0x201E,
        "dagger" to 0x2020, "Dagger" to 0x2021, "bull" to 0x2022, "hellip" to 0x2026, "permil" to 0x2030,
        "prime" to 0x2032, "Prime" to 0x2033, "lsaquo" to 0x2039, "rsaquo" to 0x203A, "euro" to 0x20AC, "trade" to 0x2122,
        "larr" to 0x2190, "uarr" to 0x2191, "rarr" to 0x2192, "darr" to 0x2193, "harr" to 0x2194,
        "lArr" to 0x21D0, "rArr" to 0x21D2, "hArr" to 0x21D4,
        "minus" to 0x2212, "infin" to 0x221E, "asymp" to 0x2248, "ne" to 0x2260, "le" to 0x2264, "ge" to 0x2265,
        "check" to 0x2713, "cross" to 0x2717,
    )

    /** HTML also accepts these few in capitals. */
    private val UPPER = setOf("QUOT", "AMP", "LT", "GT", "COPY", "REG")

    fun decode(text: String): String {
        if ('&' !in text) return text
        return REFERENCE.replace(text) { m ->
            val ref = m.groupValues[1]
            val cp = when {
                ref.startsWith("#x") || ref.startsWith("#X") -> ref.drop(2).toIntOrNull(16)
                ref.startsWith("#") -> ref.drop(1).toIntOrNull()
                else -> NAMED[ref] ?: ref.takeIf { it in UPPER }?.let { NAMED[it.lowercase()] }
            }
            cp?.takeIf(::valid)?.let { String(Character.toChars(it)) } ?: m.value
        }
    }

    /** Not NUL, a lone surrogate or past Unicode: those are no characters to show. */
    private fun valid(cp: Int) = cp in 1..0x10FFFF && cp !in 0xD800..0xDFFF
}
