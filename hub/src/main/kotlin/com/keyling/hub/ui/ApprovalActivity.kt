package com.keyling.hub.ui

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
import com.keyling.hub.HubApp
import com.keyling.hub.R
import com.keyling.hub.api.Caller
import com.keyling.hub.api.Decision

/**
 * Lets the user approve the app that started this screen. The caller is taken from
 * [getCallingPackage], which Android only provides, unforgeably, to activities started for a
 * result; anything else in the intent is ignored. Returns RESULT_OK if the app is allowed.
 */
class ApprovalActivity : ComponentActivity() {
    private val app by lazy { application as HubApp }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setHideOverlayWindows(true)
        val caller = callingPackage?.takeIf { it != packageName }?.let(app.callers::forPackage)
        if (caller == null) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }
        if (app.approvals.decision(caller.packageName) == Decision.ALLOWED) {
            setResult(Activity.RESULT_OK)
            finish()
            return
        }
        setContent {
            KeylingTheme {
                AlertDialog(
                    onDismissRequest = { decide(caller, null) },
                    title = { Text(stringResource(R.string.approval_title, caller.label)) },
                    text = { Text(stringResource(R.string.approval_text, caller.label)) },
                    confirmButton = {
                        TextButton({ decide(caller, true) }) { Text(stringResource(R.string.approval_allow)) }
                    },
                    dismissButton = {
                        TextButton({ decide(caller, false) }) { Text(stringResource(R.string.approval_deny)) }
                    },
                )
            }
        }
    }

    /** Records [allow] (null: dismissed without deciding) and finishes. */
    private fun decide(caller: Caller, allow: Boolean?) {
        when (allow) {
            true -> app.approvals.allow(caller)
            false -> app.approvals.deny(caller)
            null -> Unit
        }
        app.approvalNotifier.dismiss(caller.packageName)
        setResult(if (allow == true) Activity.RESULT_OK else Activity.RESULT_CANCELED)
        finish()
    }
}
