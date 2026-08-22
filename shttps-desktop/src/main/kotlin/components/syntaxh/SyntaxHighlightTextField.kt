package com.phlox.simpleserver.components.syntaxh

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.phlox.simpleserver.theme.AppTheme

/** Interface for syntax highlighters */
interface SyntaxHighlighter {
    fun highlight(text: String): AnnotatedString
}

@Composable
fun SyntaxHighlightTextField(
    text: String,
    onTextChange: (String) -> Unit,
    minLines: Int = 5,
    modifier: Modifier = Modifier,
    highlighter: SyntaxHighlighter = SqlSyntaxHighlighter()
) {
    val highlighted = remember(text) { highlighter.highlight(text) }

    BasicTextField(
        value = text,
        onValueChange = onTextChange,
        minLines = minLines,
        modifier = modifier
            .background(Color(0xFF1E1E1E))
            .fillMaxSize(),
        textStyle = TextStyle(
            fontFamily = FontFamily.Monospace,
            color = Color.White
        ),
        cursorBrush = SolidColor(Color.Cyan),
        decorationBox = { innerTextField ->
            // Render highlighted text behind cursor layer
            androidx.compose.foundation.text.BasicText(
                text = highlighted,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    color = Color.White
                )
            )

            innerTextField() // cursor & editing layer
        }
    )
}

@Preview
@Composable
fun SyntaxHighlightTextFieldPreview() {
    AppTheme {
        Surface {
            var sql by remember {
                mutableStateOf(
                    """
                    SELECT id, name, created_at
                    FROM user_role
                    WHERE name LIKE 'admin%'
                    ORDER BY created_at DESC
                    LIMIT 10;
                    """.trimIndent()
                )
            }
            SyntaxHighlightTextField(
                text = sql,
                onTextChange = { sql = it },
                modifier = Modifier.height(160.dp)
            )
        }
    }
}
