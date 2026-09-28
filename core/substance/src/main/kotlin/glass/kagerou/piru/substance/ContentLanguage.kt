package glass.kagerou.piru.substance

/**
 * The app's resolved content language for substance text — the requested side of
 * locale resolution. Stored rows may also be `und` (undetermined), which the
 * resolver treats as an English-tier fallback.
 *
 * Ported from `ContentLanguage` in `SubstanceReadModel.swift`.
 *
 * Spanish is a UI language whose substance *text* resolves as English — the
 * bundled database carries no Spanish prose — so only its substance titles
 * differ. Every text resolver therefore gates on [isChinese], never on
 * "not English".
 */
enum class ContentLanguage(val wireValue: String) {
    EN("en"),
    ES("es"),
    ZH_HANS("zh-Hans"),
    ZH_HANT("zh-Hant"),
    ;

    val isChinese: Boolean get() = this == ZH_HANS || this == ZH_HANT

    /**
     * Language-aware `WHERE` and `ORDER BY` fragments for a text table's
     * `language` column.
     *
     * In Chinese, matching-language text floats above source priority (the exact
     * variant first, then any Chinese row), falling back to English when no
     * Chinese row exists. In English, raw Chinese is *excluded* — only English
     * shows, plus FreeOD's machine-translated rows, which are stored as `en`.
     */
    data class Clauses(val whereAnd: String, val orderPrefix: String)

    /**
     * [column] is a fixed internal literal, and [wireValue] is an enum constant,
     * so interpolating either carries no injection surface.
     */
    fun clauses(column: String): Clauses =
        if (isChinese) {
            Clauses(
                whereAnd = "",
                orderPrefix = "($column = '$wireValue') DESC, ($column LIKE 'zh%') DESC, ",
            )
        } else {
            Clauses(whereAnd = " AND $column IN ('en', 'und') ", orderPrefix = "")
        }

    companion object {
        /**
         * Map a localization identifier ("es-419", "zh-HK", "Base") to a content
         * language; anything unrecognized is English.
         *
         * Traditional is detected by any of `hant`, `tw`, `hk`, `mo` appearing in
         * the identifier, which covers the region tags Android produces as well
         * as the script subtag.
         */
        fun fromLocalization(localization: String): ContentLanguage {
            val id = localization.lowercase()
            return when {
                id.startsWith("es") -> ES
                id.startsWith("zh") -> {
                    val traditional = listOf("hant", "tw", "hk", "mo").any { id.contains(it) }
                    if (traditional) ZH_HANT else ZH_HANS
                }
                else -> EN
            }
        }
    }
}
