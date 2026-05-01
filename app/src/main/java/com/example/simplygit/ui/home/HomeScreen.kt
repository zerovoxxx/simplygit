package com.example.simplygit.ui.home

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.simplygit.R
import com.example.simplygit.domain.model.GitOp

@Composable
fun HomeScreen(viewModel: HomeViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val safState by viewModel.safState.collectAsState()
    val credView by viewModel.credentialView.collectAsState()
    val context = LocalContext.current

    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, flags)
            }
            viewModel.onIntent(HomeIntent.PickVault(uri))
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.home_title),
                style = MaterialTheme.typography.headlineMedium,
            )

            CredentialSection(
                username = credView?.username,
                onSubmit = { u, e, pat -> viewModel.onIntent(HomeIntent.SubmitCredential(u, e, pat)) },
                onClearClipboard = { viewModel.clearClipboardNow() },
            )

            VaultSection(
                currentAbsPath = (uiState as? HomeUiState.Bound)?.localAbsPath,
                safState = safState,
                onPick = { pickerLauncher.launch(null) },
            )

            RemoteSection(
                currentUrl = (uiState as? HomeUiState.Bound)?.remoteUrl,
                onSubmit = { viewModel.onIntent(HomeIntent.SubmitRemote(it)) },
            )

            OperationsSection(
                enabled = uiState !is HomeUiState.Working,
                onClone = { viewModel.onIntent(HomeIntent.DoClone) },
                onPull = { viewModel.onIntent(HomeIntent.DoPull) },
                onCommit = { msg -> viewModel.onIntent(HomeIntent.DoCommit(msg)) },
                onPush = { viewModel.onIntent(HomeIntent.DoPush) },
            )

            StatusSection(
                uiState = uiState,
                onDismissError = { viewModel.onIntent(HomeIntent.DismissError) },
            )
        }
    }
}

@Composable
private fun CredentialSection(
    username: String?,
    onSubmit: (String, String, CharArray) -> Unit,
    onClearClipboard: () -> Unit,
) {
    var u by remember { mutableStateOf("") }
    var e by remember { mutableStateOf("") }
    var pat by remember { mutableStateOf("") }

    // Ensure we never leave the PAT buffer lying around when the composable leaves composition.
    DisposableEffect(Unit) { onDispose { pat = "" } }

    Text(stringResource(R.string.section_credential), style = MaterialTheme.typography.titleMedium)
    Text(
        text = if (username.isNullOrBlank()) stringResource(R.string.credential_missing)
        else stringResource(R.string.credential_bound, username),
    )
    OutlinedTextField(
        value = u,
        onValueChange = { u = it },
        label = { Text(stringResource(R.string.label_username)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = e,
        onValueChange = { e = it },
        label = { Text(stringResource(R.string.label_email)) },
        placeholder = {
            if (u.isNotBlank()) Text(stringResource(R.string.hint_email_default, u))
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = pat,
        onValueChange = { pat = it },
        label = { Text(stringResource(R.string.label_pat)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            enabled = u.isNotBlank() && pat.isNotBlank(),
            onClick = {
                val buf = pat.toCharArray()
                pat = ""
                onSubmit(u, e, buf)
            },
        ) { Text(stringResource(R.string.action_save_credential)) }
        OutlinedButton(onClick = onClearClipboard) {
            Text(stringResource(R.string.action_clear_clipboard))
        }
    }
}

@Composable
private fun VaultSection(
    currentAbsPath: String?,
    safState: SafResolveUiState,
    onPick: () -> Unit,
) {
    Text(stringResource(R.string.section_vault), style = MaterialTheme.typography.titleMedium)
    Text(
        text = if (currentAbsPath.isNullOrBlank()) stringResource(R.string.vault_missing)
        else stringResource(R.string.vault_bound, currentAbsPath),
    )
    when (safState) {
        SafResolveUiState.NotPrimary -> Text(
            text = stringResource(R.string.vault_not_primary),
            color = Color.Red,
        )
        SafResolveUiState.NotReadable -> Text(
            text = stringResource(R.string.vault_not_readable),
            color = Color.Red,
        )
        else -> Unit
    }
    Button(onClick = onPick) { Text(stringResource(R.string.action_pick_vault)) }
}

@Composable
private fun RemoteSection(
    currentUrl: String?,
    onSubmit: (String) -> Unit,
) {
    var url by remember(currentUrl) { mutableStateOf(currentUrl.orEmpty()) }

    Text(stringResource(R.string.section_remote), style = MaterialTheme.typography.titleMedium)
    Text(
        text = if (currentUrl.isNullOrBlank()) stringResource(R.string.remote_missing)
        else stringResource(R.string.remote_bound, currentUrl),
    )
    OutlinedTextField(
        value = url,
        onValueChange = { url = it },
        label = { Text(stringResource(R.string.label_remote_url)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        enabled = url.isNotBlank(),
        onClick = { onSubmit(url.trim()) },
    ) { Text(stringResource(R.string.action_save_remote)) }
}

@Composable
private fun OperationsSection(
    enabled: Boolean,
    onClone: () -> Unit,
    onPull: () -> Unit,
    onCommit: (String) -> Unit,
    onPush: () -> Unit,
) {
    var msg by remember { mutableStateOf("") }

    Text(stringResource(R.string.section_ops), style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(
        value = msg,
        onValueChange = { msg = it },
        label = { Text(stringResource(R.string.label_commit_message)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(enabled = enabled, onClick = onClone) { Text(stringResource(R.string.action_clone)) }
        Button(enabled = enabled, onClick = onPull) { Text(stringResource(R.string.action_pull)) }
        Button(
            enabled = enabled && msg.isNotBlank(),
            onClick = { onCommit(msg.trim()) },
        ) { Text(stringResource(R.string.action_commit)) }
        Button(enabled = enabled, onClick = onPush) { Text(stringResource(R.string.action_push)) }
    }
}

@Composable
private fun StatusSection(
    uiState: HomeUiState,
    onDismissError: () -> Unit,
) {
    Text(stringResource(R.string.section_log), style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(2.dp))
    when (uiState) {
        HomeUiState.Idle -> Text(stringResource(R.string.status_idle))
        is HomeUiState.Working -> Text(stringResource(R.string.status_working, uiState.op.name))
        is HomeUiState.Error -> {
            Text(
                text = stringResource(
                    R.string.status_error,
                    uiState.op.name,
                    errorText(uiState.messageKind),
                ),
                color = Color.Red,
            )
            OutlinedButton(onClick = onDismissError) {
                Text(stringResource(R.string.action_dismiss_error))
            }
        }
        is HomeUiState.Bound -> {
            val last = uiState.lastSuccess
            if (last != null) {
                Text(successLabel(last.op, last.description))
            } else {
                Text(stringResource(R.string.status_idle))
            }
        }
    }
}

@Composable
private fun errorText(kind: ErrorKind): String = when (kind) {
    is ErrorKind.Sanitized -> kind.message
    ErrorKind.MissingCredential -> stringResource(R.string.error_missing_credential)
    ErrorKind.MissingBinding -> stringResource(R.string.error_binding_missing)
    ErrorKind.SafPermissionRevoked -> stringResource(R.string.error_saf_permission_revoked)
}

@Composable
private fun successLabel(op: GitOp, desc: String): String = when (op) {
    GitOp.CLONE -> stringResource(R.string.status_success_clone)
    GitOp.PULL -> {
        // SPEC §5.2 / M-3: desc is a structured "<count>|<status>" produced by the
        // ViewModel; the UI composes the final localized string.
        val parts = desc.split("|", limit = 2)
        val count = parts.firstOrNull()?.toIntOrNull() ?: 0
        val status = parts.getOrNull(1).orEmpty()
        val core = if (status.isBlank()) {
            stringResource(R.string.status_pulled_commits, count)
        } else {
            stringResource(R.string.status_pulled_commits_with_status, count, status)
        }
        stringResource(R.string.status_success_pull, core)
    }
    GitOp.COMMIT -> stringResource(R.string.status_success_commit)
    GitOp.PUSH -> stringResource(R.string.status_success_push)
}
