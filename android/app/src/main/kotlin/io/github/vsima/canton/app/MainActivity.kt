// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton.app

import android.graphics.Bitmap
import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.outlined.QrCodeScanner
import android.util.Log
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.ElevatedCard
import androidx.compose.material.icons.automirrored.outlined.CallMade
import androidx.compose.material.icons.automirrored.outlined.CallReceived
import androidx.compose.foundation.text.selection.SelectionContainer
import android.text.format.DateUtils
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.vsima.canton.wallet.TokenStandardClient
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val model by lazy {
        WalletModel(getSharedPreferences("wallet", MODE_PRIVATE))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val autoAccept = intent.getBooleanExtra("autoAccept", false)
        intent.getStringExtra("host")?.let { WalletEnvironment.hostBridge = it }
        setContent {
            WalletTheme {
                WalletApp(model, autoAccept)
            }
        }
    }
}

/// Light-only by product choice: Material You's light dynamic palette on
/// Android 12+, the M3 light scheme elsewhere.
@Composable
fun WalletTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scheme =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) dynamicLightColorScheme(context)
        else lightColorScheme()
    MaterialTheme(colorScheme = scheme, content = content)
}

private enum class Section(val label: String, val icon: ImageVector) {
    Portfolio("Portfolio", Icons.Outlined.AccountBalanceWallet),
    Inbox("Inbox", Icons.Outlined.Inbox),
    Send("Send", Icons.AutoMirrored.Outlined.Send),
    Receive("Receive", Icons.Outlined.QrCode),
    History("History", Icons.Outlined.History),
}

@Composable
fun WalletApp(model: WalletModel, autoAccept: Boolean) {
    LaunchedEffect(Unit) {
        model.onboard()
        while (true) {
            model.refresh()
            if (autoAccept) {
                model.inbox.firstOrNull()?.let { model.accept(it) }
            }
            delay(3_000)
        }
    }

    when (val phase = model.phase) {
        WalletModel.Phase.Fresh, WalletModel.Phase.Onboarding -> CenteredMessage(
            title = "Creating your wallet…",
            body = "A signing key is being generated in this device's keystore and registered as your Canton party.",
            spinner = true,
        )
        is WalletModel.Phase.Failed -> CenteredMessage(
            title = "Can't reach ${WalletEnvironment.name}",
            body = phase.message,
            spinner = false,
        )
        WalletModel.Phase.Ready -> WalletTabs(model)
    }
}

@Composable
private fun CenteredMessage(title: String, body: String, spinner: Boolean) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (spinner) CircularProgressIndicator()
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WalletTabs(model: WalletModel) {
    var section by remember { mutableStateOf(Section.Portfolio) }

    // NavigationSuiteScaffold adapts the navigation itself: bottom bar on
    // phones, navigation rail on tablets/foldables/landscape.
    NavigationSuiteScaffold(
        navigationSuiteItems = {
            Section.entries.forEach { item ->
                item(
                    selected = section == item,
                    onClick = { section = item },
                    label = { Text(item.label) },
                    icon = {
                        if (item == Section.Inbox && model.inbox.isNotEmpty()) {
                            BadgedBox(badge = { Badge { Text("${model.inbox.size}") } }) {
                                Icon(item.icon, contentDescription = item.label)
                            }
                        } else {
                            Icon(item.icon, contentDescription = item.label)
                        }
                    },
                )
            }
        },
    ) {
        Scaffold(
            topBar = { TopAppBar(title = { Text(section.label) }) },
        ) { padding ->
            Column(Modifier.padding(padding)) {
                when (section) {
                    Section.Portfolio -> PortfolioScreen(model)
                    Section.Inbox -> InboxScreen(model)
                    Section.Send -> SendScreen(model)
                    Section.Receive -> ReceiveScreen(model)
                    Section.History -> HistoryScreen(model)
                }
            }
        }
    }
}

@Composable
private fun PortfolioScreen(model: WalletModel) {
    var showSigner by remember { mutableStateOf(false) }
    if (showSigner) {
        AlertDialog(
            onDismissRequest = { showSigner = false },
            confirmButton = { TextButton({ showSigner = false }) { Text("Done") } },
            title = { Text(model.signerLabel) },
            text = {
                Text(
                    if (model.signerLabel.contains("keystore") && !model.signerLabel.contains("emulator"))
                        "Your signing key was generated inside this device's secure hardware. It cannot be exported, synced, or read — every transaction is signed by the hardware itself.\n\nParty: ${model.partyId}"
                    else
                        "Software key for development in the emulator. On a physical device the key is hardware-resident (TEE or StrongBox) and non-exportable.\n\nParty: ${model.partyId}"
                )
            },
        )
    }
    LazyColumn {
        item {
            ElevatedCard(Modifier.fillMaxWidth().padding(16.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "${model.totalAmulet.stripTrailingZeros().toPlainString()} CC",
                        style = MaterialTheme.typography.displaySmall,
                    )
                    OutlinedButton(onClick = { showSigner = true }) {
                        Text(model.signerLabel, style = MaterialTheme.typography.labelMedium)
                    }
                    model.lastError?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        item {
            SectionHeader(
                "Holdings",
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
            )
        }
        val groups = model.holdings
            .groupBy { it.instrumentId.id to (it.lock != null) }
            .map { (key, group) ->
                HoldingGroup(
                    label = key.first,
                    locked = key.second,
                    amount = group.fold(java.math.BigDecimal.ZERO) { acc, h -> acc + h.amount },
                    count = group.size,
                    contractId = group.singleOrNull()?.contractId,
                )
            }
            .sortedWith(compareBy({ it.locked }, { it.label }))
        items(groups, key = { "${it.label}|${it.locked}" }) { group ->
            ListItem(
                headlineContent = {
                    Text("${group.amount.stripTrailingZeros().toPlainString()} ${group.label}")
                },
                supportingContent = {
                    Text(
                        group.contractId?.let { it.take(24) + "…" } ?: "${group.count} holding contracts",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                trailingContent = { if (group.locked) Text("locked") },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier,
    )
}

@Composable
private fun ReceiptRow(label: String, value: String, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text(
            value,
            fontFamily = if (mono) FontFamily.Monospace else null,
            style = if (mono) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
        )
    }
}

/** Holdings are discrete UTXO-style contracts on the ledger; the portfolio
 *  rolls them up to one row per instrument (locked apart). */
private data class HoldingGroup(
    val label: String,
    val locked: Boolean,
    val amount: java.math.BigDecimal,
    val count: Int,
    val contractId: String?,
)

@Composable
private fun InboxScreen(model: WalletModel) {
    val scope = rememberCoroutineScope()
    LazyColumn {
        if (model.inbox.isEmpty()) {
            item { ListItem(headlineContent = { Text("No pending offers.") }) }
        }
        items(model.inbox, key = { it.contractId }) { offer ->
            ListItem(
                headlineContent = {
                    Text("${offer.transfer.amount.toPlainString()} ${offer.transfer.instrumentId.id}")
                },
                supportingContent = {
                    Column {
                        Text(
                            "from ${offer.transfer.sender.take(30)}…",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        offer.transfer.meta[WalletModel.MEMO_KEY]?.takeIf { it.isNotBlank() }?.let {
                            Text("“$it”", style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            "expires ${DateUtils.getRelativeTimeSpanString(offer.transfer.executeBefore.toEpochMilli())}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row {
                            Button(
                                onClick = { model.accept(offer) },
                                enabled = offer.contractId !in model.processing,
                            ) { Text("Accept") }
                            Spacer(Modifier.size(8.dp))
                            OutlinedButton(
                                onClick = { model.reject(offer) },
                                enabled = offer.contractId !in model.processing,
                            ) { Text("Reject") }
                        }
                    }
                },
            )
            HorizontalDivider()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SendScreen(model: WalletModel) {
    val scope = rememberCoroutineScope()
    model.lastSend?.let { receipt ->
        ModalBottomSheet(onDismissRequest = { model.lastSend = null }) {
            Column(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFF2E7D32),
                    modifier = Modifier.size(56.dp),
                )
                Text("Transfer submitted", style = MaterialTheme.typography.titleLarge)
                Text(
                    "It settles instantly if the receiver is preapproved; otherwise it awaits their acceptance.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                HorizontalDivider()
                ReceiptRow("Amount", "${receipt.amount.stripTrailingZeros().toPlainString()} CC")
                ReceiptRow("To", receipt.receiver.take(28) + "…", mono = true)
                if (receipt.memo.isNotBlank()) ReceiptRow("Memo", receipt.memo)
                ReceiptRow(
                    "At",
                    java.time.format.DateTimeFormatter
                        .ofLocalizedTime(java.time.format.FormatStyle.MEDIUM)
                        .withZone(java.time.ZoneId.systemDefault())
                        .format(receipt.at),
                )
                Button(
                    onClick = { model.lastSend = null },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Done") }
            }
        }
    }
    var receiver by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var memo by remember { mutableStateOf("") }

    Column(
        Modifier.padding(16.dp).verticalScroll(rememberScrollState()).imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val context = LocalContext.current
        OutlinedTextField(
            value = receiver,
            onValueChange = { receiver = it },
            label = { Text("Recipient party id") },
            trailingIcon = {
                IconButton(onClick = {
                    val options = GmsBarcodeScannerOptions.Builder()
                        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                        .build()
                    GmsBarcodeScanning.getClient(context, options).startScan()
                        .addOnSuccessListener { code -> code.rawValue?.let { receiver = it.trim() } }
                        .addOnFailureListener { Log.i("WALLET", "scan failed: $it") }
                }) { Icon(Icons.Outlined.QrCodeScanner, contentDescription = "Scan QR code") }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = amount,
            onValueChange = { amount = it },
            label = { Text("Amount (CC)") },
            supportingText = {
                Row {
                    Text("Available: ${model.totalAmulet.stripTrailingZeros().toPlainString()} CC")
                    Spacer(Modifier.size(12.dp))
                    Text(
                        "MAX",
                        modifier = androidx.compose.ui.Modifier.clickable {
                            amount = model.totalAmulet.stripTrailingZeros().toPlainString()
                        },
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = memo,
            onValueChange = { memo = it },
            label = { Text("Memo (optional)") },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                val value = amount.toBigDecimalOrNull() ?: return@Button
                model.sendAsync(receiver.trim(), value, memo)
                amount = ""
                memo = ""
            },
            enabled = !model.busy && receiver.isNotBlank() && amount.toBigDecimalOrNull() != null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (model.busy) "Sending…" else "Send") }
    }
}

@Composable
private fun ReceiveScreen(model: WalletModel) {
    val party = model.partyId ?: return
    val qr = remember(party) { qrBitmap(party) }
    Column(
        Modifier.fillMaxWidth().padding(24.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        qr?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "Party id QR code",
                modifier = Modifier.size(220.dp),
            )
        }
        Text("Your party id", style = MaterialTheme.typography.titleSmall)
        Text(party, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        val clipboard = LocalClipboardManager.current
        OutlinedButton(onClick = { clipboard.setText(AnnotatedString(party)) }) {
            Text("Copy party id")
        }
        HorizontalDivider()
        val scope = rememberCoroutineScope()
        when {
            model.preapproval != null -> Text(
                "⚡ Instant receiving active — transfers settle with no inbox step.",
                style = MaterialTheme.typography.bodySmall,
            )
            model.preapprovalRequested -> Text(
                "Waiting for your validator to approve instant receiving…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> Button(
                onClick = { model.requestInstantReceiveAsync() },
                enabled = !model.busy,
            ) { Text("Enable instant receiving") }
        }
        Text(
            "Senders create a transfer to this party; it arrives in your Inbox to accept — or instantly with preapproval.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryScreen(model: WalletModel) {
    var selected by remember { mutableStateOf<TokenStandardClient.HoldingsChange?>(null) }
    selected?.let { change ->
        ModalBottomSheet(onDismissRequest = { selected = null }) {
            Column(
                Modifier.fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, bottom = 32.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    if (change.created.isEmpty()) "Sent / spent" else "Received",
                    style = MaterialTheme.typography.titleLarge,
                )
                SectionHeader("When")
                Text(
                    java.time.format.DateTimeFormatter
                        .ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM)
                        .withZone(java.time.ZoneId.systemDefault())
                        .format(change.recordTime),
                )
                if (change.created.isNotEmpty()) {
                    SectionHeader("Credited")
                    change.created.forEach { holding ->
                        ReceiptRow(
                            "${holding.amount.stripTrailingZeros().toPlainString()} ${holding.instrumentId.id}",
                            holding.contractId.take(16) + "…",
                            mono = true,
                        )
                    }
                }
                if (change.archivedContractIds.isNotEmpty()) {
                    SectionHeader("Spent inputs")
                    change.archivedContractIds.forEach { cid ->
                        Text(
                            cid.take(32) + "…",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                SectionHeader("Update id")
                SelectionContainer {
                    Text(
                        change.updateId,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Button(
                    onClick = { selected = null },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) { Text("Done") }
            }
        }
    }
    LazyColumn {
        if (model.history.isEmpty()) {
            item { ListItem(headlineContent = { Text("No activity yet.") }) }
        }
        items(model.history, key = { it.updateId }) { change ->
            val credited = change.created.fold(java.math.BigDecimal.ZERO) { acc, holding ->
                acc + holding.amount
            }
            val received = change.created.isNotEmpty()
            ListItem(
                modifier = Modifier.clickable { selected = change },
                leadingContent = {
                    Icon(
                        if (received) Icons.AutoMirrored.Outlined.CallReceived
                        else Icons.AutoMirrored.Outlined.CallMade,
                        contentDescription = null,
                        tint = if (received) Color(0xFF2E7D32)
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                headlineContent = { Text(if (received) "Received" else "Sent / spent") },
                supportingContent = {
                    Text(
                        DateUtils.getRelativeTimeSpanString(change.recordTime.toEpochMilli()).toString(),
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
                trailingContent = {
                    Column(horizontalAlignment = Alignment.End) {
                        if (credited.signum() > 0) {
                            Text(
                                "+${credited.stripTrailingZeros().toPlainString()} CC",
                                color = Color(0xFF2E7D32),
                            )
                        }
                        if (change.archivedContractIds.isNotEmpty()) {
                            Text(
                                "${change.archivedContractIds.size} input" +
                                    (if (change.archivedContractIds.size == 1) "" else "s") + " spent",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                },
            )
            HorizontalDivider()
        }
    }
}

private fun qrBitmap(text: String): Bitmap? = try {
    val size = 512
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
    for (x in 0 until size) {
        for (y in 0 until size) {
            bitmap.setPixel(
                x, y,
                if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE,
            )
        }
    }
    bitmap
} catch (_: Exception) {
    null
}
