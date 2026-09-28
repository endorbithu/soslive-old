package info.soslive.stream.domain

/** Emergency contact phone numbers - international format, same rule as the legacy app and the API. */
object SosContacts {
    private val PHONE = Regex("^\\+[0-9]{10,13}$")
    private val SEPARATORS = Regex("[,;\\n]+")
    const val MAX_CONTACTS = 10

    fun isValid(number: String): Boolean = PHONE.matches(number)

    /** Splits user input (comma / semicolon / newline separated), strips spaces and dashes. */
    fun parse(input: String): ParseResult {
        val numbers = input.split(SEPARATORS)
            .map { it.replace(" ", "").replace("-", "").trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        val invalid = numbers.filterNot(::isValid)
        return when {
            invalid.isNotEmpty() -> ParseResult.Invalid(invalid)
            numbers.size > MAX_CONTACTS -> ParseResult.TooMany
            else -> ParseResult.Valid(numbers)
        }
    }

    fun format(numbers: List<String>): String = numbers.joinToString("\n")

    sealed interface ParseResult {
        data class Valid(val numbers: List<String>) : ParseResult
        data class Invalid(val numbers: List<String>) : ParseResult
        data object TooMany : ParseResult
    }
}
