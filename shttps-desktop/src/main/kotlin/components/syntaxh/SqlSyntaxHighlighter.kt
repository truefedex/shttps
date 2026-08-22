package com.phlox.simpleserver.components.syntaxh

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

class SqlSyntaxHighlighter : SyntaxHighlighter {

    private val keywordColor = Color(0xFFFF9800)
    private val stringColor = Color(0xFF4CAF50)
    private val numberColor = Color(0xFF03A9F4)
    private val commentColor = Color(0xFF9E9E9E)

    private val keywordRegex = Regex(
        """\b(SELECT|FROM|WHERE|GROUP|BY|ORDER|LIMIT|INSERT|INTO|VALUES|UPDATE|SET|DELETE|
           CREATE|TABLE|DROP|ALTER|AND|OR|NOT|NULL|AS|ON|JOIN|LEFT|RIGHT|INNER|OUTER|
           PRIMARY|KEY|FOREIGN|REFERENCES|UNIQUE|INTEGER|TEXT|REAL|BLOB|BOOLEAN)\b""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.COMMENTS)
    )

    private val stringRegex = Regex("'(?:''|[^'])*'")
    private val numberRegex = Regex("""\b[0-9]+(\.[0-9]+)?\b""")
    private val lineCommentRegex = Regex("""--.*?(?=\n|$)""")
    private val blockCommentRegex = Regex("""(?s)/\*.*?\*/""")

    override fun highlight(text: String): AnnotatedString {
        val builder = AnnotatedString.Builder(text)

        // ----------------------------------
        // 1. FIND ALL COMMENT RANGES FIRST
        // ----------------------------------
        val commentRanges = mutableListOf<IntRange>()

        fun addCommentRanges(regex: Regex) {
            regex.findAll(text).forEach { match ->
                commentRanges += match.range
                builder.addStyle(
                    SpanStyle(
                        color = commentColor,
                        fontStyle = FontStyle.Italic
                    ),
                    match.range.first,
                    match.range.last + 1
                )
            }
        }

        addCommentRanges(blockCommentRegex)
        addCommentRanges(lineCommentRegex)

        fun isInsideComment(range: IntRange): Boolean =
            commentRanges.any { comment -> range.first >= comment.first && range.last <= comment.last }

        // ----------------------------------
        // 2. NORMAL TOKENS (SKIP COMMENTS)
        // ----------------------------------
        fun apply(regex: Regex, color: Color, bold: Boolean = false, italic: Boolean = false) {
            regex.findAll(text).forEach { match ->
                if (isInsideComment(match.range)) return@forEach   // ← skip

                builder.addStyle(
                    SpanStyle(
                        color = color,
                        fontWeight = if (bold) FontWeight.Bold else null,
                        fontStyle = if (italic) FontStyle.Italic else null
                    ),
                    match.range.first,
                    match.range.last + 1
                )
            }
        }

        apply(stringRegex, stringColor)
        apply(numberRegex, numberColor)
        apply(keywordRegex, keywordColor, bold = true)

        return builder.toAnnotatedString()
    }
}