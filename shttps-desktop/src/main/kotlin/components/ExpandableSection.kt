package com.phlox.simpleserver.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phlox.simpleserver.theme.AppTheme

/**
 * Settings section with a clickable header that expands/collapses its content.
 * Counterpart of the Android app's ExpandableSectionView. The expanded state is not kept here
 * on purpose: the caller owns it, so that it can be persisted (see AppConfig.collapsedSections).
 */
@Composable
fun ExpandableSection(
    title: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = modifier.fillMaxWidth().padding(16.dp)) {
        val arrowRotation by animateFloatAsState(
            targetValue = if (expanded) 0f else -90f,
            animationSpec = tween(300)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onExpandedChange(!expanded) }
                .padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colors.secondaryVariant,
                modifier = Modifier.rotate(arrowRotation)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = title,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colors.secondaryVariant
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.fillMaxWidth(), content = content)
        }
    }
}

@Preview(name = "ExpandableSection - expanded")
@Composable
fun ExpandableSectionExpandedPreview() {
    AppTheme {
        Surface {
            var expanded by remember { mutableStateOf(true) }
            ExpandableSection(
                title = "Server settings",
                expanded = expanded,
                onExpandedChange = { expanded = it }
            ) {
                Text("Port: 8080")
                Text("Root: /home/user/shared")
            }
        }
    }
}

@Preview(name = "ExpandableSection - collapsed")
@Composable
fun ExpandableSectionCollapsedPreview() {
    AppTheme {
        Surface {
            var expanded by remember { mutableStateOf(false) }
            ExpandableSection(
                title = "Server settings",
                expanded = expanded,
                onExpandedChange = { expanded = it }
            ) {
                Text("Port: 8080")
            }
        }
    }
}
