// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.ai

/**
 * Sriboard v2.2: converts common Markdown annotations to plain text BEFORE the AI
 * result is committed to the app's text field.
 *
 * The IME can only insert plain text into other apps — it cannot apply rich
 * formatting (bold, colors) inside WhatsApp/Telegram/etc. So the correct behavior
 * is: follow the annotations (newlines, lists, links, headings) but drop the
 * visible markers, so the user never sees literal \n, *, # or ` characters.
 */
object MarkdownCleaner {

    fun clean(raw: String): String {
        var t = raw
        // Windows line endings
        t = t.replace("\r\n", "\n")
        // 1. literal escape sequences some APIs send as text: \n \t \" \r
        t = t.replace("\\n", "\n").replace("\\t", "\t").replace("\\\"", "\"").replace("\\r", "")
        // 2. fenced code blocks: ```lang ... ``` -> keep content only
        t = FENCE_CODE.replace(t) { m -> m.groupValues[1] }
        // 3. bold-italic, bold, underline, strikethrough, italic
        t = BOLD_ITALIC.replace(t, "$1")
        t = BOLD.replace(t, "$1")
        t = UNDERLINE.replace(t, "$1")
        t = STRIKE.replace(t, "$1")
        t = ITALIC.replace(t, "$1")
        // 4. inline code
        t = INLINE_CODE.replace(t, "$1")
        // 5. images -> alt text; links -> "text (url)"
        t = IMAGE.replace(t) { m -> m.groupValues[1] }
        t = LINK.replace(t) { m ->
            val text = m.groupValues[1]
            val url = m.groupValues[2]
            if (url.isBlank()) text else "$text ($url)"
        }
        // 6. headings: "### Title" -> "Title"
        t = HEADING.replace(t, "$1")
        // 7. blockquotes: "> text" -> "text"
        t = QUOTE.replace(t, "$1")
        // 8. bullet lists: "- /*/+ item" -> "• item"
        t = BULLET.replace(t, "• $1")
        // 9. horizontal rules on their own line -> removed
        t = HR.replace(t, "")
        // 10. <br> / <br/> -> newline
        t = BR.replace(t, "\n")
        // 11. collapse 3+ newlines to 2, trim ends
        t = MULTI_NL.replace(t, "\n\n").trim()
        return t
    }

    private val FENCE_CODE = Regex("""```[^`\n]*\n([\s\S]*?)```""", RegexOption.MULTILINE)
    private val BOLD_ITALIC = Regex("""\*\*\*(.+?)\*\*\*""", RegexOption.DOT_MATCHES_ALL)
    private val BOLD = Regex("""\*\*(.+?)\*\*""", RegexOption.DOT_MATCHES_ALL)
    private val UNDERLINE = Regex("""__(.+?)__""", RegexOption.DOT_MATCHES_ALL)
    private val STRIKE = Regex("""~~(.+?)~~""", RegexOption.DOT_MATCHES_ALL)
    private val ITALIC = Regex("""(?<!\*)\*(?!\s)(.+?)(?<!\s)\*(?!\*)""", RegexOption.DOT_MATCHES_ALL)
    private val INLINE_CODE = Regex("""`([^`]+?)`""")
    private val IMAGE = Regex("""!\[([^\]]*)\]\([^)]*\)""")
    private val LINK = Regex("""\[([^\]]*)\]\(([^)\s]+)(?:\s+"[^"]*")?\)""")
    private val HEADING = Regex("""(?m)^\s{0,3}#{1,6}\s+""")
    private val QUOTE = Regex("""(?m)^\s{0,3}>\s?""")
    private val BULLET = Regex("""(?m)^\s*[-*+]\s+""")
    private val HR = Regex("""(?m)^\s*(?:---+|\*\*\*+|___+)\s*$""")
    private val BR = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
    private val MULTI_NL = Regex("""\n{3,}""")
}
