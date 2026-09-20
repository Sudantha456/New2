package com.app.youtube.lite.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.app.youtube.lite.R
import androidx.compose.ui.unit.dp

/**
 * The empty / error / offline states, in one place so that every screen says the same thing the
 * same way.
 *
 * These are the screens users see when something is wrong, so they get real content: an icon, a
 * short title, a sentence that says what actually happened, and exactly one action. The action is
 * always the *useful* one for that state — a sign-in button on an auth-gated feed, a retry on a
 * network failure — never a generic "OK" that dismisses the problem without fixing it.
 */
object StateViews {

    /** Full-screen message with an optional action button. */
    @Composable
    fun Message(
        title: String,
        message: String,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null,
        icon: ImageVector = Icons.Filled.Warning,
        modifier: Modifier = Modifier,
    ) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier
                    .widthIn(max = 360.dp)
                    .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(40.dp),
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                if (actionLabel != null && onAction != null) {
                    Spacer(Modifier.height(18.dp))
                    Button(onClick = onAction, shape = RoundedCornerShape(24.dp)) {
                        Text(actionLabel)
                    }
                }
            }
        }
    }

    /** Full-screen retry state. */
    @Composable
    fun Error(
        message: String,
        onRetry: () -> Unit,
        modifier: Modifier = Modifier,
    ) {
        Message(
            title = stringResource(R.string.feed_error),
            message = message,
            actionLabel = stringResource(R.string.feed_retry),
            onAction = onRetry,
            icon = Icons.Filled.Refresh,
            modifier = modifier,
        )
    }

    /** A slim strip pinned above the content: the app keeps working, it just cannot fetch. */
    @Composable
    fun OfflineBanner(modifier: Modifier = Modifier) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.padding(horizontal = 4.dp))
                Text(
                    text = stringResource(R.string.feed_offline),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}
