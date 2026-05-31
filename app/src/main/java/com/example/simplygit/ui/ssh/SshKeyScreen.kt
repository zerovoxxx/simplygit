package com.example.simplygit.ui.ssh

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.simplygit.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SshKeyScreen(
    onBack: () -> Unit,
    viewModel: SshKeyViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ssh_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text("←", style = MaterialTheme.typography.titleLarge)
                    }
                },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    enabled = !uiState.loading,
                    onClick = { viewModel.generate() },
                ) { Text(stringResource(R.string.ssh_action_generate)) }
                OutlinedButton(
                    enabled = !uiState.loading,
                    onClick = { viewModel.showImportDialog(true) },
                ) { Text(stringResource(R.string.ssh_action_import)) }
                if (uiState.loading) {
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.height(24.dp))
                    }
                }
            }
            HorizontalDivider()
            Text(
                stringResource(R.string.ssh_section_existing),
                style = MaterialTheme.typography.titleMedium,
            )
            if (uiState.keys.isEmpty()) {
                Text(stringResource(R.string.ssh_empty))
            } else {
                uiState.keys.forEach { key ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.padding(end = 8.dp)) {
                            Text(key.keyId, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                key.fingerprintSha256,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        OutlinedButton(onClick = { viewModel.delete(key.keyId) }) {
                            Text(stringResource(R.string.ssh_action_delete))
                        }
                    }
                }
            }
        }
    }

    uiState.justGenerated?.let { preview ->
        GeneratedKeyDialog(preview = preview, onDismiss = { viewModel.dismissGenerated() })
    }

    if (uiState.importDialog) {
        ImportKeyDialog(
            onDismiss = { viewModel.showImportDialog(false) },
            onSubmit = { text, passphrase ->
                viewModel.showImportDialog(false)
                viewModel.importPasted(text, passphrase)
            },
        )
    }

    uiState.errorMessageKey?.let { key ->
        val resId = when (key) {
            "ssh_generate_failed" -> R.string.ssh_error_generate_failed
            "ssh_import_invalid_format" -> R.string.ssh_error_invalid_format
            "ssh_import_failed" -> R.string.ssh_error_import_failed
            "ssh_delete_in_use" -> R.string.ssh_error_delete_in_use
            else -> R.string.ssh_error_import_failed
        }
        AlertDialog(
            onDismissRequest = { viewModel.dismissError() },
            title = { Text(stringResource(R.string.ssh_error_title)) },
            text = { Text(stringResource(resId), color = Color(0xFFB71C1C)) },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissError() }) {
                    Text(stringResource(R.string.conflict_ok))
                }
            },
        )
    }
}

@Composable
private fun GeneratedKeyDialog(preview: GeneratedKeyPreview, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ssh_generated_title)) },
        text = {
            Column {
                Text(stringResource(R.string.ssh_generated_keyid, preview.keyId))
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.ssh_generated_fingerprint, preview.fingerprintSha256))
                Spacer(Modifier.height(6.dp))
                Text(preview.publicKeyOpenssh, style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(preview.publicKeyOpenssh))
            }) { Text(stringResource(R.string.ssh_action_copy_public)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.conflict_close))
            }
        },
    )
}

@Composable
private fun ImportKeyDialog(
    onDismiss: () -> Unit,
    onSubmit: (text: String, passphrase: String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ssh_import_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.ssh_import_label)) },
                    modifier = Modifier.fillMaxWidth().height(160.dp),
                )
                // BUG-001 fix (bug_report_20260503_snao): accept an optional
                // passphrase so encrypted OpenSSH private keys can be
                // imported + unlocked in one shot. Blank = no passphrase.
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text(stringResource(R.string.ssh_import_passphrase_label)) },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input
                        .PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.contains("BEGIN OPENSSH PRIVATE KEY"),
                onClick = { onSubmit(text, passphrase) },
            ) { Text(stringResource(R.string.ssh_import_submit)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.conflict_close))
            }
        },
    )
}
