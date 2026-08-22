package com.phlox.simpleserver.screens.qr

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Card
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CopyAll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.phlox.simpleserver.shttps_desktop.generated.resources.*
import com.phlox.simpleserver.utils.copyToClipboard
import io.nayuki.qrcodegen.QrCode
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

@Composable
fun QrScreen(
    text: String,
    navController: NavHostController,
    onNavigateBack: () -> Unit
) {
    var qrCode by remember(text) { mutableStateOf<QrCode?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val noTextMsg = stringResource(Res.string.qr_no_text)
    
    // Generate QR code when text changes
    LaunchedEffect(text) {
        try {
            if (text.isNotEmpty()) {
                qrCode = QrCode.encodeText(text, QrCode.Ecc.MEDIUM)
                errorMessage = null
            } else {
                qrCode = null
                errorMessage = noTextMsg
            }
        } catch (e: Exception) {
            qrCode = null
            errorMessage = getString(Res.string.qr_failed, e.message ?: "")
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colors.background)
            .padding(16.dp)
    ) {
        // Header with back button
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onNavigateBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    tint = MaterialTheme.colors.onBackground,
                    contentDescription = stringResource(Res.string.cd_back)
                )
            }
            Text(
                text = stringResource(Res.string.qr_code),
                style = MaterialTheme.typography.h5,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colors.onBackground,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = { copyToClipboard(text) }
            ) {
                Icon(
                    imageVector = Icons.Default.CopyAll,
                    tint = MaterialTheme.colors.onBackground,
                    contentDescription = stringResource(Res.string.cd_copy)
                )
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Display text
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                elevation = 4.dp
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = stringResource(Res.string.original_text),
                        style = MaterialTheme.typography.subtitle2,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = text.ifEmpty { noTextMsg },
                        style = MaterialTheme.typography.body1,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // QR Code display
            if (errorMessage != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    backgroundColor = MaterialTheme.colors.error.copy(alpha = 0.1f),
                    elevation = 4.dp
                ) {
                    Text(
                        text = errorMessage!!,
                        style = MaterialTheme.typography.body1,
                        color = MaterialTheme.colors.error,
                        modifier = Modifier.padding(16.dp),
                        textAlign = TextAlign.Center
                    )
                }
            } else if (qrCode != null) {
                Card(
                    modifier = Modifier
                        .padding(16.dp),
                    backgroundColor = Color.White,
                    elevation = 8.dp
                ) {
                    QrCodeCanvas(
                        qrCode = qrCode!!,
                        modifier = Modifier
                            .size(300.dp)
                            .padding(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun QrCodeCanvas(
    qrCode: QrCode,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val canvasSize = size.minDimension
        val qrSize = qrCode.size
        val cellSize = canvasSize / qrSize
        
        // Draw QR code
        for (y in 0 until qrSize) {
            for (x in 0 until qrSize) {
                if (qrCode.getModule(x, y)) {
                    val left = x * cellSize
                    val top = y * cellSize
                    drawRect(
                        color = Color.Black,
                        topLeft = Offset(left, top),
                        size = Size(cellSize, cellSize)
                    )
                }
            }
        }
        
        // Draw border
        drawRect(
            color = Color.White,
            style = Stroke(width = 2.dp.toPx()),
            size = Size(canvasSize, canvasSize)
        )
    }
}
