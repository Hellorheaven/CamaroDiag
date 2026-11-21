package com.camarodiag.gen5.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.camarodiag.gen5.R

@Composable
fun FunctionalTestsScreen(
    state: FunctionalTestState,
    onRunAll: () -> Unit,
    onStop: () -> Unit,
    onExport: () -> Unit
) {
    val fr = LocalConfiguration.current.locales[0].language == "fr"
    val passed = state.checks.count { it.outcome == FunctionalTestOutcome.PASSED }
    val failed = state.checks.count { it.outcome == FunctionalTestOutcome.FAILED }
    val untested = state.checks.count { it.outcome == FunctionalTestOutcome.NOT_TESTED }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.functional_tests_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.functional_tests_readonly), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = onRunAll, enabled = !state.running) {
                        Text(stringResource(R.string.functional_tests_run))
                    }
                    if (state.running) {
                        OutlinedButton(onClick = onStop) {
                            Text(stringResource(R.string.functional_tests_stop))
                        }
                        CircularProgressIndicator(modifier = Modifier.height(22.dp))
                    } else {
                        OutlinedButton(onClick = onExport, enabled = state.finishedAtMillis != null) {
                            Text(stringResource(R.string.functional_tests_export))
                        }
                    }
                }
                if (state.checks.isNotEmpty()) {
                    Text(stringResource(R.string.functional_tests_summary, passed, failed, untested))
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
        items(state.checks, key = { it.key }) { check ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (fr) check.labelFr else check.labelEn, style = MaterialTheme.typography.titleSmall)
                    Text(
                        when (check.outcome) {
                            FunctionalTestOutcome.PASSED -> stringResource(R.string.functional_tests_passed)
                            FunctionalTestOutcome.FAILED -> stringResource(R.string.functional_tests_failed)
                            FunctionalTestOutcome.NOT_TESTED -> stringResource(R.string.functional_tests_skipped)
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = when (check.outcome) {
                            FunctionalTestOutcome.PASSED -> MaterialTheme.colorScheme.primary
                            FunctionalTestOutcome.FAILED -> MaterialTheme.colorScheme.error
                            FunctionalTestOutcome.NOT_TESTED -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    Text(if (fr) check.detailFr else check.detailEn, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}
