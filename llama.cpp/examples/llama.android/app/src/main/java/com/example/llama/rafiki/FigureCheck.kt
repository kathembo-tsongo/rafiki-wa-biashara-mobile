package com.example.llama.rafiki

/**
 * Removes money amounts and percentages from a model answer when their number is not in the passages the model
 * was given -- small models invent plausible fees. Example: "costs approximately KES 100" with no 100 in the
 * passages becomes "costs approximately *(amount not in the documents)*".
 */
object FigureCheck {
    private val figure = Regex(
        """(?i)(\*\*)?(?:(?:KES|KSh[s]?\.?|Kshs\.?|USD|US\$|\$)\s?\d[\d,]*(?:\.\d+)?""" +
            """(?:\s?(?:million|billion|m|bn)\b)?|\d[\d,]*(?:\.\d+)?\s?(?:%|percent\b|per cent\b)|""" +
            """\d[\d,]*(?:\.\d+)?\s?(?:shillings|bob)\b)(\*\*)?""")
    private val number = Regex("""\d[\d,]*(?:\.\d+)?""")

    /** [allowed] = the passages' text; null means "don't check". */
    fun apply(text: String, allowed: String?): String {
        if (allowed == null) return text
        val pool = allowed.replace(",", "")
        var removed = 0
        val out = figure.replace(text) { m ->
            val n = number.find(m.value)?.value?.replace(",", "") ?: return@replace m.value
            if (Regex("""(?<![\d.])${Regex.escape(n)}(?!\d)""").containsMatchIn(pool)) m.value
            else { removed++; "*(amount not in the documents)*" }
        }
        if (removed == 0) return out
        val what = if (removed == 1) "1 figure that is not in the documents was" else "$removed figures that are not in the documents were"
        return out.trimEnd() + "\n\n⚠️ *$what removed -- check current amounts with the relevant office.*"
    }
}
