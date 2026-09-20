package com.app.youtube.lite.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.app.youtube.lite.R
import com.app.youtube.lite.auth.LoginOutcome
import com.app.youtube.lite.auth.LoginWebView
import com.app.youtube.lite.core.di.AppGraph

/**
 * The sign-in screen: Google's own page, in a WebView we throw away.
 *
 * The app never sees the user's password — it is typed into `accounts.google.com` and posted from
 * there. What comes back is the YouTube session cookie set, which [LoginWebView] harvests directly
 * from the platform `CookieManager` the moment Google redirects back to a YouTube page.
 *
 * The "Done" button exists because Google's sign-in flow is not a single page: it can end on a
 * consent screen, a 2FA prompt or a "choose your account" list, and the auto-detection that waits
 * for a YouTube URL cannot know that the user considers themselves finished. Tapping it forces the
 * harvest — which is why the finish signal is the screen's only job here.
 */
@Composable
fun LoginScreen(
    graph: AppGraph,
    onSignedIn: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var finishSignal by rememberSaveable { mutableIntStateOf(0) }
    var status by remember { mutableStateOf<String?>(null) }
    var harvesting by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(
                text = stringResource(R.string.auth_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.auth_explanation),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        LoginWebView(
            onOutcome = { outcome ->
                harvesting = false
                when (outcome) {
                    is LoginOutcome.Success -> {
                        graph.auth.save(outcome.cookies)
                        status = null
                        onSignedIn()
                    }
                    is LoginOutcome.Failure -> status = outcome.message
                }
            },
            finishSignal = finishSignal,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 12.dp),
        )

        status?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = stringResource(R.string.auth_finished),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onCancel) { Text(stringResource(R.string.auth_cancel)) }
            Button(
                onClick = {
                    harvesting = true
                    finishSignal++
                },
                shape = RoundedCornerShape(20.dp),
                enabled = !harvesting,
            ) {
                Text(
                    stringResource(
                        if (harvesting) R.string.auth_harvesting else R.string.auth_done,
                    ),
                )
            }
        }
    }
}
