package com.phlox.simpleserver.screens.home.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.AlertDialog
import androidx.compose.material.ButtonDefaults.textButtonColors
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phlox.simpleserver.shttps_desktop.generated.resources.Res
import com.phlox.simpleserver.shttps_desktop.generated.resources.cancel
import com.phlox.simpleserver.shttps_desktop.generated.resources.enter_first_user_credentials
import com.phlox.simpleserver.shttps_desktop.generated.resources.first_user_account_hint
import com.phlox.simpleserver.shttps_desktop.generated.resources.hide
import com.phlox.simpleserver.shttps_desktop.generated.resources.password
import com.phlox.simpleserver.shttps_desktop.generated.resources.password_cannot_be_empty
import com.phlox.simpleserver.shttps_desktop.generated.resources.set
import com.phlox.simpleserver.shttps_desktop.generated.resources.set_credentials
import com.phlox.simpleserver.shttps_desktop.generated.resources.show
import com.phlox.simpleserver.shttps_desktop.generated.resources.username
import com.phlox.simpleserver.shttps_desktop.generated.resources.username_cannot_be_empty
import org.jetbrains.compose.resources.stringResource

@Composable
fun UserCredentialsDialog(
    onConfirm: (username: String, password: String) -> Unit,
    onDismiss: () -> Unit
) {
    val usernameText = remember { mutableStateOf("") }
    val passwordText = remember { mutableStateOf("") }
    val showPassword = remember { mutableStateOf(false) }
    val isUsernameError = remember { mutableStateOf(false) }
    val isPasswordError = remember { mutableStateOf(false) }
    val usernameErrorMessage = remember { mutableStateOf("") }
    val passwordErrorMessage = remember { mutableStateOf("") }
    val usernameEmptyMsg = stringResource(Res.string.username_cannot_be_empty)
    val passwordEmptyMsg = stringResource(Res.string.password_cannot_be_empty)

    // Validate inputs
    fun validateInputs(): Boolean {
        var isValid = true
        
        // Validate username
        if (usernameText.value.trim().isEmpty()) {
            isUsernameError.value = true
            usernameErrorMessage.value = usernameEmptyMsg
            isValid = false
        } else {
            isUsernameError.value = false
            usernameErrorMessage.value = ""
        }
        
        // Validate password
        if (passwordText.value.isEmpty()) {
            isPasswordError.value = true
            passwordErrorMessage.value = passwordEmptyMsg
            isValid = false
        } else {
            isPasswordError.value = false
            passwordErrorMessage.value = ""
        }
        
        return isValid
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(Res.string.set_credentials),
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                Text(
                    text = stringResource(Res.string.enter_first_user_credentials),
                    fontSize = 14.sp,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.height(16.dp))
                
                // Username field
                TextField(
                    value = usernameText.value,
                    onValueChange = { newValue ->
                        usernameText.value = newValue
                        if (isUsernameError.value) {
                            validateInputs()
                        }
                    },
                    label = { Text(stringResource(Res.string.username)) },
                    isError = isUsernameError.value,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        keyboardType = KeyboardType.Text
                    )
                )
                if (isUsernameError.value) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = usernameErrorMessage.value,
                        color = MaterialTheme.colors.error,
                        fontSize = 12.sp
                    )
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Password field
                TextField(
                    value = passwordText.value,
                    onValueChange = { newValue ->
                        passwordText.value = newValue
                        if (isPasswordError.value) {
                            validateInputs()
                        }
                    },
                    label = { Text(stringResource(Res.string.password)) },
                    isError = isPasswordError.value,
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
                if (isPasswordError.value) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = passwordErrorMessage.value,
                        color = MaterialTheme.colors.error,
                        fontSize = 12.sp
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(Res.string.first_user_account_hint),
                    fontSize = 12.sp,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                )
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
                        if (validateInputs()) {
                            onConfirm(usernameText.value.trim(), passwordText.value)
                        }
                    }
                ) {
                    Text(stringResource(Res.string.set))
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

