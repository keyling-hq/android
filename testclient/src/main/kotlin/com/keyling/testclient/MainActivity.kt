package com.keyling.testclient

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.keyling.client.Keyling
import com.keyling.client.KeylingClient
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Exercises the Keyling client API: binding, approval, typing and state changes. */
class MainActivity : ComponentActivity() {
    private val log = mutableStateListOf<String>()
    private var state by mutableStateOf(Keyling.STATE_UNKNOWN)
    private var connected by mutableStateOf(false)

    private val client: KeylingClient by lazy {
        KeylingClient(this, object : KeylingClient.Listener {
            override fun onConnected() {
                connected = true
                log("bound, hub API version ${client.apiVersion}")
            }

            override fun onDisconnected() {
                connected = false
                log("binding lost")
            }

            override fun onStateChanged(state: Int) {
                this@MainActivity.state = state
                log("state ${stateName(state)}")
            }
        })
    }

    private fun log(line: String) {
        log.add(0, "${LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))}  $line")
    }

    private val onResult = KeylingClient.ResultCallback { result, message ->
        log("result ${resultName(result)}${message?.let { ": $it" } ?: ""}")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (!KeylingClient.isHubInstalled(this)) log("the Keyling hub is not installed")
        setContent {
            val dark = isSystemInDarkTheme()
            val context = LocalContext.current
            MaterialTheme(if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)) {
                Screen()
            }
        }
    }

    override fun onDestroy() {
        client.unbind()
        super.onDestroy()
    }

    @Composable
    private fun Screen() {
        var text by rememberSaveable { mutableStateOf("Hello from the test client!\n") }
        var layout by rememberSaveable { mutableStateOf("") }
        val approval = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            log("approval: ${if (it.resultCode == Activity.RESULT_OK) "allowed" else "not allowed"}")
        }

        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Keyling test client", style = MaterialTheme.typography.headlineSmall)
                Text("Bound: $connected · hub state: ${stateName(state)}")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ if (!client.bind()) log("bind failed") }) { Text("Bind") }
                    OutlinedButton({ client.unbind(); connected = false; log("unbound") }) { Text("Unbind") }
                    OutlinedButton({
                        try {
                            approval.launch(KeylingClient.approvalIntent())
                        } catch (e: android.content.ActivityNotFoundException) {
                            log("no approval screen: is the hub installed?")
                        }
                    }) { Text("Request approval") }
                }
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text("Text") }, minLines = 3)
                OutlinedTextField(
                    layout, { layout = it }, Modifier.fillMaxWidth(),
                    label = { Text("Layout (empty: hub default)") }, singleLine = true,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ log("type ${text.length} chars"); client.typeText(text, layout.ifEmpty { null }, onResult) }) {
                        Text("Type")
                    }
                    OutlinedButton({
                        log("type once ${text.length} chars")
                        KeylingClient.typeOnce(this@MainActivity, text, layout.ifEmpty { null }, onResult)
                    }) { Text("Type once") }
                    OutlinedButton({ log("abort"); client.abort() }) { Text("Abort") }
                    OutlinedButton({ log.clear() }) { Text("Clear log") }
                }
                log.forEach { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

private fun stateName(state: Int) = when (state) {
    Keyling.STATE_UNKNOWN -> "unknown"
    Keyling.STATE_NOT_PAIRED -> "not paired"
    Keyling.STATE_DISCONNECTED -> "disconnected"
    Keyling.STATE_CONNECTING -> "connecting"
    Keyling.STATE_CONNECTED -> "connected"
    Keyling.STATE_NEEDS_ATTENTION -> "needs attention"
    else -> "state $state"
}

private fun resultName(result: Int) = when (result) {
    Keyling.RESULT_OK -> "OK"
    Keyling.RESULT_NOT_APPROVED -> "NOT_APPROVED"
    Keyling.RESULT_DENIED -> "DENIED"
    Keyling.RESULT_UNAVAILABLE -> "UNAVAILABLE"
    Keyling.RESULT_FAILED -> "FAILED"
    Keyling.RESULT_ABORTED -> "ABORTED"
    Keyling.RESULT_INVALID -> "INVALID"
    else -> "result $result"
}
