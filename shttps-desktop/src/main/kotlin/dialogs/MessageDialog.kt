package com.phlox.simpleserver.dialogs

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import com.phlox.simpleserver.shttps_desktop.generated.resources.Res
import com.phlox.simpleserver.shttps_desktop.generated.resources.ok
import org.jetbrains.compose.resources.stringResource

data class MessageDialogData(
    val title: String = "",
    val message: String = "",
    val onDismiss: (() -> Unit)? = null,
    val buttons: List<DialogButton>? = null
)

data class DialogButton(
    val text: String,
    val onClick: () -> Unit
)

@Composable
fun MessageDialog(
    data: MessageDialogData
) {
    var showContent by remember { mutableStateOf(false) }
    var hideContent by remember { mutableStateOf(false) }
    
    LaunchedEffect(data) {
        if (data.title.isNotEmpty() && data.message.isNotEmpty())
            showContent = true
    }
    
    LaunchedEffect(hideContent) {
        if (hideContent) {
            delay(300)
            data.onDismiss?.let { it() }
            showContent = false
            hideContent = false
        }
    }
    
    if (showContent) {
        Dialog(onDismissRequest = {
            hideContent = true
        }) {
            AnimatedVisibility(
                visible = showContent && !hideContent,
                enter = slideInVertically(
                    initialOffsetY = { -it },
                    animationSpec = tween(300, easing = FastOutSlowInEasing)
                ) + fadeIn(animationSpec = tween(300)),
                exit = slideOutVertically(
                    targetOffsetY = { -it },
                    animationSpec = tween(300, easing = FastOutSlowInEasing)
                ) + fadeOut(animationSpec = tween(300))
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    elevation = 8.dp
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = data.title,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                        
                        Text(
                            text = data.message,
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            modifier = Modifier.padding(bottom = 24.dp)
                        )
                        
                        // Custom buttons or default Ok button
                        if (data.buttons != null && data.buttons.isNotEmpty()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                data.buttons.forEach { button ->
                                    Button(
                                        onClick = {
                                            button.onClick()
                                            hideContent = true
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(button.text)
                                    }
                                }
                            }
                        } else {
                            Button(
                                onClick = {
                                    hideContent = true
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(Res.string.ok))
                            }
                        }
                    }
                }
            }
        }
    }
}
