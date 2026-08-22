package com.phlox.simpleserver.screens.home.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.AlertDialog
import androidx.compose.material.ButtonDefaults.textButtonColors
import androidx.compose.material.Checkbox
import androidx.compose.material.CheckboxDefaults
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phlox.simpleserver.shttps_desktop.generated.resources.Res
import com.phlox.simpleserver.shttps_desktop.generated.resources.cancel
import com.phlox.simpleserver.shttps_desktop.generated.resources.hide
import com.phlox.simpleserver.shttps_desktop.generated.resources.key_password_for
import com.phlox.simpleserver.shttps_desktop.generated.resources.keystore_password
import com.phlox.simpleserver.shttps_desktop.generated.resources.enter_key_password
import com.phlox.simpleserver.shttps_desktop.generated.resources.enter_keystore_password
import com.phlox.simpleserver.shttps_desktop.generated.resources.ok
import com.phlox.simpleserver.shttps_desktop.generated.resources.password
import com.phlox.simpleserver.shttps_desktop.generated.resources.password_cannot_be_empty
import com.phlox.simpleserver.shttps_desktop.generated.resources.show
import com.phlox.simpleserver.shttps_desktop.generated.resources.the_same_password_for_the_key_and_the_keystore
import org.jetbrains.compose.resources.stringResource

@Composable
fun PasswordDialog(
    title: String,
    message: String? = null,
    initialPassword: String = "",
    allowSamePasswordOption: Boolean = false,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val passwordText = remember { mutableStateOf(initialPassword) }
    val isSamePassword = remember { mutableStateOf(allowSamePasswordOption && initialPassword.isNotEmpty()) }
    val showPassword = remember { mutableStateOf(false) }
    val isError = remember { mutableStateOf(false) }
    val errorMessage = remember { mutableStateOf("") }
    val passwordEmptyMsg = stringResource(Res.string.password_cannot_be_empty)

    // Validate password
    fun validatePassword(text: String): Boolean {
        if (text.isBlank()) {
            isError.value = true
            errorMessage.value = passwordEmptyMsg
            return false
        }
        isError.value = false
        errorMessage.value = ""
        return true
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                if (message != null) {
                    Text(
                        text = message,
                        fontSize = 14.sp,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                
                TextField(
                    value = passwordText.value,
                    onValueChange = { newValue ->
                        if (isSamePassword.value && initialPassword.isNotEmpty()) {
                            // Prevent changing password if "same password" is checked
                            return@TextField
                        }
                        passwordText.value = newValue
                        validatePassword(newValue)
                    },
                    label = { Text(stringResource(Res.string.password)) },
                    isError = isError.value,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = if (showPassword.value) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        TextButton(
                            onClick = { showPassword.value = !showPassword.value },
                            colors = textButtonColors(
                                contentColor = MaterialTheme.colors.onSurface
                            )
                        ) {
                            Text(if (showPassword.value) stringResource(Res.string.hide) else stringResource(Res.string.show))
                        }
                    }
                )
                
                if (isError.value) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = errorMessage.value,
                        color = MaterialTheme.colors.error,
                        fontSize = 12.sp
                    )
                }
                
                if (allowSamePasswordOption) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Checkbox(
                        checked = isSamePassword.value,
                        onCheckedChange = { isChecked ->
                            isSamePassword.value = isChecked
                            if (isChecked && initialPassword.isNotEmpty()) {
                                passwordText.value = initialPassword
                            }
                        },
                        colors = CheckboxDefaults.colors()
                    )
                    Text(
                        text = stringResource(Res.string.the_same_password_for_the_key_and_the_keystore),
                        fontSize = 12.sp,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
                    )
                }
            }
        },
        buttons = {
            Column(
                modifier = Modifier.fillMaxWidth().padding(all = 8.dp),
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
            ) {
                TextButton(
                    colors = textButtonColors(
                        contentColor = MaterialTheme.colors.onSurface
                    ),
                    onClick = {
                        if (validatePassword(passwordText.value)) {
                            onConfirm(passwordText.value)
                        }
                    },
                    enabled = !isError.value
                ) {
                    Text(stringResource(Res.string.ok))
                }
                TextButton(
                    colors = textButtonColors(
                        contentColor = MaterialTheme.colors.onSurface
                    ),
                    onClick = onDismiss
                ) {
                    Text(stringResource(Res.string.cancel))
                }
            }
        }
    )
}

@Composable
fun KeystorePasswordDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    PasswordDialog(
        title = stringResource(Res.string.keystore_password),
        message = stringResource(Res.string.enter_keystore_password),
        onConfirm = onConfirm,
        onDismiss = onDismiss
    )
}

@Composable
fun KeyPasswordDialog(
    keystorePassword: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    PasswordDialog(
        title = stringResource(Res.string.key_password_for),
        message = stringResource(Res.string.enter_key_password),
        initialPassword = keystorePassword,
        allowSamePasswordOption = true,
        onConfirm = onConfirm,
        onDismiss = onDismiss
    )
}
