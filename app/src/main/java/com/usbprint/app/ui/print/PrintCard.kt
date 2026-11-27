package com.usbprint.app.ui.print

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.usbprint.app.R
import com.usbprint.app.print.PrintUiState
import com.usbprint.core.print.JobState
import kotlinx.coroutines.delay

/** The bottom print card: wavy progress, page count, percentage and cancel. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PrintCard(
    ui: PrintUiState,
    onCancel: () -> Unit,
    onPrintAgain: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val job = ui.job
    if (job is JobState.Cancelled) {
        LaunchedEffect(job) {
            delay(1500)
            onClose()
        }
    }

    Surface(
        modifier = modifier
            .navigationBarsPadding()
            .padding(12.dp)
            .widthIn(max = 640.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
        shadowElevation = 12.dp,
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (job) {
                    JobState.Done -> {
                        Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(12.dp))
                    }
                    is JobState.Failed -> {
                        Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(12.dp))
                    }
                    else -> {
                        com.usbprint.app.ui.common.StatusLed(
                            com.usbprint.app.ui.common.Led.BUSY,
                            live = job !is JobState.Cancelled,
                            size = 10.dp,
                        )
                        Spacer(Modifier.width(12.dp))
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(headline(job, ui.title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        com.usbprint.app.ui.common.bidi(ui.printerName),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            when (job) {
                JobState.Preparing, JobState.Cancelling -> LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                is JobState.Printing -> {
                    val progress by animateFloatAsState(job.fraction, label = "print-progress")
                    LinearWavyProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                }
                is JobState.Failed -> Text(job.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                else -> Unit
            }

            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    detail(job),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                when (job) {
                    JobState.Preparing, is JobState.Printing ->
                        OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
                    JobState.Done, is JobState.Failed -> {
                        OutlinedButton(onClick = onClose) { Text(stringResource(R.string.close)) }
                        Button(onClick = onPrintAgain) {
                            Text(stringResource(if (job is JobState.Done) R.string.print_again else R.string.try_again))
                        }
                    }
                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun headline(job: JobState, title: String): String = when (job) {
    JobState.Done -> stringResource(R.string.print_done)
    is JobState.Failed -> stringResource(R.string.print_failed)
    JobState.Cancelled -> stringResource(R.string.print_cancelled)
    JobState.Cancelling -> stringResource(R.string.print_cancelling)
    else -> stringResource(R.string.print_printing, com.usbprint.app.ui.common.bidi(title))
}

@Composable
private fun detail(job: JobState): String = when (job) {
    JobState.Preparing -> stringResource(R.string.print_preparing)
    is JobState.Printing -> stringResource(R.string.print_page_of, job.page, job.totalPages, (job.fraction * 100).toInt())
    else -> ""
}
