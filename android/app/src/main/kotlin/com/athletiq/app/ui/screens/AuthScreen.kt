package com.athletiq.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.athletiq.app.ui.components.PillTone
import com.athletiq.app.ui.components.TelemetryCard
import com.athletiq.app.ui.components.UnavailableBanner
import com.athletiq.app.ui.theme.AthletiqColors

@Composable
fun AuthScreen(
    isBusy: Boolean,
    errorMessage: String?,
    onLogin: (baseUrl: String, email: String, password: String) -> Unit,
    onBootstrapInstitution: (
        baseUrl: String,
        instName: String,
        instCode: String,
        adminName: String,
        adminEmail: String,
        adminPassword: String,
    ) -> Unit,
) {
    var isBootstrapMode by remember { mutableStateOf(false) }
    var serverUrl by remember { mutableStateOf("http://127.0.0.1:8000") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var institutionName by remember { mutableStateOf("") }
    var institutionCode by remember { mutableStateOf("") }
    var adminName by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AthletiqColors.SurfaceCanvas)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(AthletiqColors.PrimaryCobalt),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Bolt,
                    contentDescription = "AthletiQ",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp),
                )
            }
            Column {
                Text(
                    text = "AthletiQ",
                    style = MaterialTheme.typography.headlineMedium,
                    color = AthletiqColors.TextPrimary,
                )
                Text(
                    text = "Contactless Sports Assessment Platform",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AthletiqColors.TextSecondary,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilterChip(
                selected = !isBootstrapMode,
                onClick = { isBootstrapMode = false },
                label = { Text("Staff Sign In", style = MaterialTheme.typography.labelLarge) },
            )
            FilterChip(
                selected = isBootstrapMode,
                onClick = { isBootstrapMode = true },
                label = { Text("First-Time Setup", style = MaterialTheme.typography.labelLarge) },
            )
        }

        if (errorMessage != null) {
            UnavailableBanner(
                title = "Authentication Failed",
                message = errorMessage,
                tone = PillTone.ERROR,
            )
        }

        TelemetryCard {
            Text(
                text = if (isBootstrapMode) "Initialize Institution & Admin" else "Examiner / Coach Sign In",
                style = MaterialTheme.typography.titleMedium,
                color = AthletiqColors.TextPrimary,
            )
            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                label = { Text("Backend API URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(10.dp))

            if (isBootstrapMode) {
                OutlinedTextField(
                    value = institutionName,
                    onValueChange = { institutionName = it },
                    label = { Text("Institution Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = institutionCode,
                    onValueChange = { institutionCode = it },
                    label = { Text("Institution Code (e.g. SCH_01)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = adminName,
                    onValueChange = { adminName = it },
                    label = { Text("Administrator Full Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(10.dp))
            }

            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Staff Email") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(if (isBootstrapMode) "Password (min 12 chars)" else "Password") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(16.dp))

            val canSubmit = !isBusy && serverUrl.isNotBlank() && email.isNotBlank() && password.isNotBlank() &&
                (!isBootstrapMode || (institutionName.isNotBlank() && institutionCode.isNotBlank() && adminName.isNotBlank() && password.length >= 12))

            Button(
                onClick = {
                    if (isBootstrapMode) {
                        onBootstrapInstitution(serverUrl, institutionName, institutionCode, adminName, email, password)
                    } else {
                        onLogin(serverUrl, email, password)
                    }
                },
                enabled = canSubmit,
                colors = ButtonDefaults.buttonColors(containerColor = AthletiqColors.PrimaryCobalt),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                if (isBusy) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(20.dp),
                    )
                } else {
                    Text(
                        text = if (isBootstrapMode) "Create Institution & Sign In" else "Sign In",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}
