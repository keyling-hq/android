package com.keyling.hub.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import bridge.Result
import com.keyling.hub.HubApp
import com.keyling.hub.R
import com.keyling.hub.api.ApprovalStore
import com.keyling.hub.dongle.DongleController
import com.keyling.hub.dongle.DongleState
import kotlinx.coroutines.launch

@Composable
fun MainScreen(controller: DongleController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by controller.state.collectAsState()
    var message by remember { mutableStateOf<String?>(null) }
    var afterPermission by remember { mutableStateOf<(() -> Unit)?>(null) }

    val pairingNotCompleted = stringResource(R.string.pairing_not_completed)
    val permissionDenied = stringResource(R.string.permission_denied)
    val removeBondHint = stringResource(R.string.hint_remove_bond)

    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            scope.launch {
                if (!controller.onAssociationResult(data)) message = pairingNotCompleted
            }
        }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        controller.refresh()
        if (granted[Manifest.permission.BLUETOOTH_CONNECT] == true) {
            afterPermission?.invoke()
        } else {
            message = permissionDenied
        }
        afterPermission = null
    }
    val pairingFailed = stringResource(R.string.pairing_failed)

    fun withPermissions(action: () -> Unit) {
        val needed = buildList {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.all { context.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }) {
            action()
        } else {
            afterPermission = action
            permissions.launch(needed.toTypedArray())
        }
    }

    fun pair() = withPermissions {
        message = null
        controller.associate(
            onChooser = { sender -> scope.launch { chooser.launch(IntentSenderRequest.Builder(sender).build()) } },
            onError = { error -> scope.launch { message = pairingFailed.format(error) } },
        )
    }

    fun connect() = withPermissions { controller.connect() }

    fun forget() {
        message = if (controller.forget()) null else removeBondHint
    }

    fun openBluetoothSettings() = context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
            DongleCard(
                controller = controller,
                state = state,
                onPair = ::pair,
                onConnect = ::connect,
                onDisconnect = controller::disconnect,
                onForget = ::forget,
                onGrant = { withPermissions {} },
                onBluetoothSettings = ::openBluetoothSettings,
            )
            message?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            if (state is DongleState.Connected) TestCard(controller)
            AppsCard((context.applicationContext as HubApp).approvals)
        }
    }
}

@Composable
private fun AppsCard(approvals: ApprovalStore) {
    val apps by approvals.approvals.collectAsState()
    if (apps.isEmpty()) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.apps_title), style = MaterialTheme.typography.titleMedium)
            Detail(stringResource(R.string.apps_hint))
            apps.forEach { app ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(app.label, style = MaterialTheme.typography.bodyLarge)
                        Detail(stringResource(if (app.allowed) R.string.apps_allowed else R.string.apps_denied))
                    }
                    TextButton({ approvals.remove(app.packageName) }) { Text(stringResource(R.string.apps_remove)) }
                }
            }
        }
    }
}

@Composable
private fun DongleCard(
    controller: DongleController,
    state: DongleState,
    onPair: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onForget: () -> Unit,
    onGrant: () -> Unit,
    onBluetoothSettings: () -> Unit,
) {
    val status by controller.status.collectAsState()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state is DongleState.Connecting || state is DongleState.Pairing) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
                Text(stringResource(stateText(state)), style = MaterialTheme.typography.titleMedium)
            }

            if (state is DongleState.Connected) {
                Detail(stringResource(R.string.detail_firmware, state.hello.firmware_version))
                status?.let {
                    Detail(stringResource(if (it.usb_mounted) R.string.detail_usb_mounted else R.string.detail_usb_not_mounted))
                    if (it.layout.isNotEmpty()) Detail(stringResource(R.string.detail_layout, it.layout))
                    if (it.typing) Detail(stringResource(R.string.detail_typing))
                }
            }
            hintText(state)?.let { Detail(stringResource(it)) }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (state) {
                    DongleState.NotPaired -> Button(onPair) { Text(stringResource(R.string.button_pair)) }
                    DongleState.NoPermission -> Button(onGrant) { Text(stringResource(R.string.button_grant)) }
                    DongleState.BluetoothOff ->
                        Button(onBluetoothSettings) { Text(stringResource(R.string.button_bluetooth_settings)) }
                    DongleState.Idle -> {
                        Button(onConnect) { Text(stringResource(R.string.button_connect)) }
                        OutlinedButton(onForget) { Text(stringResource(R.string.button_forget)) }
                    }
                    DongleState.Pairing -> Unit
                    DongleState.Connecting ->
                        OutlinedButton(onDisconnect) { Text(stringResource(R.string.button_cancel)) }
                    is DongleState.Connected -> {
                        OutlinedButton(onDisconnect) { Text(stringResource(R.string.button_disconnect)) }
                        TextButton(onForget) { Text(stringResource(R.string.button_forget)) }
                    }
                    DongleState.BondLost -> {
                        Button(onBluetoothSettings) { Text(stringResource(R.string.button_bluetooth_settings)) }
                        OutlinedButton(onForget) { Text(stringResource(R.string.button_forget)) }
                        TextButton(onConnect) { Text(stringResource(R.string.button_retry)) }
                    }
                    DongleState.PhoneBondMissing ->
                        Button(onForget) { Text(stringResource(R.string.button_forget)) }
                }
            }
        }
    }
}

@Composable
private fun Detail(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun stateText(state: DongleState): Int = when (state) {
    DongleState.NotPaired -> R.string.state_not_paired
    DongleState.NoPermission -> R.string.state_no_permission
    DongleState.BluetoothOff -> R.string.state_bluetooth_off
    DongleState.Idle -> R.string.state_idle
    DongleState.Pairing -> R.string.state_pairing
    DongleState.Connecting -> R.string.state_connecting
    is DongleState.Connected -> R.string.state_connected
    DongleState.BondLost -> R.string.state_bond_lost
    DongleState.PhoneBondMissing -> R.string.state_phone_bond_missing
}

private fun hintText(state: DongleState): Int? = when (state) {
    DongleState.NotPaired -> R.string.hint_not_paired
    DongleState.Pairing -> R.string.hint_pairing
    DongleState.BondLost -> R.string.hint_bond_lost
    DongleState.PhoneBondMissing -> R.string.hint_phone_bond_missing
    else -> null
}

@Composable
private fun TestCard(controller: DongleController) {
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    val sent = stringResource(R.string.result_typed)
    val failed = stringResource(R.string.result_failed)

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.test_title), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.test_hint)) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        result = null
                        scope.launch {
                            result = try {
                                val r = controller.typeText(text)
                                if (r == Result.RESULT_OK) sent else failed.format(r.name)
                            } catch (e: Exception) {
                                failed.format(e.message)
                            }
                        }
                    },
                    enabled = text.isNotEmpty(),
                ) { Text(stringResource(R.string.button_type)) }
                OutlinedButton(onClick = { scope.launch { controller.abort() } }) {
                    Text(stringResource(R.string.button_abort))
                }
                result?.let { Detail(it) }
            }
        }
    }
}
