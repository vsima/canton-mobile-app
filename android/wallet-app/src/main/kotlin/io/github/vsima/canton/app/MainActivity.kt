// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton.app

import android.content.Intent
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
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material.icons.outlined.SmartToy
import android.util.Log
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Switch
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.coroutines.launch
import androidx.compose.material.icons.automirrored.outlined.CallMade
import androidx.compose.material.icons.automirrored.outlined.CallReceived
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.foundation.text.selection.SelectionContainer
import android.text.format.DateUtils
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.vsima.canton.wallet.Holding
import io.github.vsima.canton.wallet.TokenStandardClient
import io.github.vsima.canton.wallet.TransferDirection
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import io.github.vsima.canton.dapp.wallet.DappApproval
import io.github.vsima.canton.dapp.wallet.DappApprovalRequest
import io.github.vsima.canton.dapp.wallet.DappCommandSummary
import io.github.vsima.canton.dapp.wallet.DappPeer
import androidx.compose.material.icons.outlined.Link
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
        WalletModel(
            store = io.github.vsima.canton.wallet.android.AndroidKeystoreWalletStore(this),
            legacyPrefs = getSharedPreferences("wallet", MODE_PRIVATE),
            agentStore = AgentStore(filesDir),
            onSilentAgentActivity = { activity ->
                AgentNotifications.notify(applicationContext, activity)
            },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AgentNotifications.ensureChannel(this)
        // Notification permission up front: the spend policy's silent
        // outcomes (auto-approve, refusal) surface through notifications.
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            registerForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
            ) {}.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        val autoAccept = intent.getBooleanExtra("autoAccept", false)
        // The dev host override is sticky: a deep-link launch (from a camera)
        // carries no extras, so persist the last host and restore it, keeping
        // the wallet on the adb-reverse loopback for LocalNet.
        val devPrefs = getSharedPreferences("devconfig", MODE_PRIVATE)
        val host = intent.getStringExtra("host")
        if (host != null) {
            WalletEnvironment.hostBridge = host
            devPrefs.edit().putString("hostBridge", host).apply()
        } else {
            devPrefs.getString("hostBridge", null)?.let { WalletEnvironment.hostBridge = it }
        }
        setContent {
            WalletTheme {
                WalletApp(model, autoAccept)
            }
        }
        handleDeepLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    /** A `canton-checkout:` VIEW intent (from a camera / QR scanner): hand the
     *  checkout URL to the model; the Send screen fetches and prefills it. */
    private fun handleDeepLink(intent: Intent?) {
        val data = intent?.dataString ?: return
        if (data.startsWith("canton-checkout:")) {
            model.requestCheckout(data)
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

// Three sections, down from five: Inbox and History fold into Activity (one
// feed answering "what has been happening in my wallet", which also gives
// agent events a first-class, badge-able surface), and Transfer folds into
// Portfolio as Send/Receive actions on the balance it moves. Connect is the
// dApp hub: pairing, sessions, per-dApp spending limits.
private enum class Section(val label: String, val icon: ImageVector) {
    Portfolio("Portfolio", Icons.Outlined.AccountBalanceWallet),
    Activity("Activity", Icons.Outlined.History),
    Agents("Agents", Icons.Outlined.SmartToy),
}

/** Filter chips on the Activity feed. */
private enum class ActivityFilter(val label: String) {
    All("All"),
    Transfers("Transfers"),
    Requests("Requests"),
    Dapps("dApps"),
}

/** The two pages of the Transfer section, paged by a segmented control. */
private enum class TransferPage(val label: String) {
    Send("Send"),
    Receive("Receive"),
}

@Composable
fun WalletApp(model: WalletModel, autoAccept: Boolean) {
    LaunchedEffect(Unit) {
        model.onboard()
        while (true) {
            model.refresh()
            if (autoAccept) {
                model.inbox.firstOrNull { it.contractId !in model.processing }
                    ?.let { model.accept(it) }
            }
            delay(3_000)
        }
    }

    when (val phase = model.phase) {
        WalletModel.Phase.Fresh, WalletModel.Phase.Onboarding -> CenteredMessage(
            title = "Creating your wallet…",
            body = "A signing key is being generated in this device's keystore and registered " +
                "as your Canton party. The key never leaves the device.",
            spinner = true,
        )
        is WalletModel.Phase.Failed -> CenteredMessage(
            title = "Can't reach ${WalletEnvironment.name}",
            body = phase.message,
            spinner = false,
            error = true,
        )
        WalletModel.Phase.Ready -> WalletTabs(model)
    }
}

@Composable
private fun CenteredMessage(title: String, body: String, spinner: Boolean, error: Boolean = false) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (spinner) CircularProgressIndicator()
        if (error) {
            Icon(
                Icons.Outlined.WifiOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(40.dp),
            )
        }
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 6,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WalletTabs(model: WalletModel) {
    var section by remember { mutableStateOf(Section.Portfolio) }

    // A checkout deep link routes to Portfolio, whose Send sheet prefills it.
    LaunchedEffect(model.pendingCheckoutUrl) {
        if (model.pendingCheckoutUrl != null) section = Section.Portfolio
    }

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
                        // Pending inbox requests need action; unseen silent
                        // agent events need attention. Both land on Activity.
                        val badgeCount = if (item == Section.Activity) {
                            model.inbox.size + model.unseenAgentEvents
                        } else {
                            0
                        }
                        if (badgeCount > 0) {
                            BadgedBox(badge = { Badge { Text("$badgeCount") } }) {
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
                    Section.Activity -> ActivityScreen(model)
                    Section.Agents -> ConnectScreen(model)
                }
            }
        }
    }
    // A WalletConnect request can arrive on any tab, so the approval sheet is
    // rendered globally, above the tabs.
    WcApprovalSheet(model)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PortfolioScreen(model: WalletModel) {
    var showSigner by remember { mutableStateOf(false) }
    if (showSigner) {
        ModalBottomSheet(onDismissRequest = { showSigner = false }) {
            Column(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(model.signerLabel, style = MaterialTheme.typography.titleLarge)
                SectionHeader("What this means")
                if (model.hardwareSigner) {
                    Text(
                        "Your signing key was generated inside this device's secure hardware. " +
                            "It cannot be exported, synced, backed up, or read — by this app, by Google, " +
                            "or by anyone. Every transaction is signed by the hardware itself."
                    )
                    Text(
                        "Keys that sync between devices leave the hardware as encrypted blobs. This one never does.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "Software key for development in the emulator. On a physical device the key is " +
                            "hardware-resident (TEE or StrongBox) and non-exportable."
                    )
                }
                SectionHeader("Party")
                SelectionContainer {
                    Text(
                        model.partyId ?: "—",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                SectionHeader("Network")
                ReceiptRow("Environment", WalletEnvironment.name)
                ReceiptRow("Participant", "${WalletEnvironment.hostBridge}:2901", mono = true)
                Text(
                    "DevNet and bring-your-own-validator arrive with real authentication flows.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = { showSigner = false },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) { Text("Done") }
            }
        }
    }
    // Progressive disclosure of the UTXO model: the rolled-up balance row
    // opens a sheet listing the discrete holding contracts backing it.
    var selectedGroup by remember { mutableStateOf<HoldingGroup?>(null) }
    selectedGroup?.let { group ->
        ModalBottomSheet(onDismissRequest = { selectedGroup = null }) {
            Column(
                Modifier.fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, bottom = 32.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    if (group.locked) "${group.label} (locked)" else group.label,
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    "Backed by ${group.count} contract" + if (group.count == 1) "" else "s",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                group.holdings.forEachIndexed { index, holding ->
                    if (index > 0) HorizontalDivider()
                    HoldingContractRow(holding, group.label)
                }
                Button(
                    onClick = { selectedGroup = null },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) { Text("Done") }
            }
        }
    }
    var refreshing by remember { mutableStateOf(false) }
    val refreshScope = rememberCoroutineScope()
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            refreshScope.launch {
                refreshing = true
                model.refresh()
                refreshing = false
            }
        },
    ) {
    // Send and Receive live with the balance they move; the old Transfer
    // tab's pager opens as a sheet. A checkout deep link opens it on Send.
    var transferPage by remember { mutableStateOf<TransferPage?>(null) }
    LaunchedEffect(model.pendingCheckoutUrl) {
        if (model.pendingCheckoutUrl != null) transferPage = TransferPage.Send
    }
    transferPage?.let { page ->
        ModalBottomSheet(onDismissRequest = { transferPage = null }) {
            TransferScreen(model, initialPage = page)
        }
    }
    LazyColumn {
        item {
            ElevatedCard(Modifier.fillMaxWidth().padding(16.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "${model.totalAmulet.cc()} CC",
                        style = MaterialTheme.typography.displaySmall,
                    )
                    OutlinedButton(onClick = { showSigner = true }) {
                        Text(model.signerLabel, style = MaterialTheme.typography.labelMedium)
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Button(
                            onClick = { transferPage = TransferPage.Send },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Outlined.CallMade,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.size(6.dp))
                            Text("Send")
                        }
                        FilledTonalButton(
                            onClick = { transferPage = TransferPage.Receive },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Outlined.CallReceived,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.size(6.dp))
                            Text("Receive")
                        }
                    }
                }
            }
        }
        item {
            // The faucet affordance lives beside the Holdings header once
            // funded (low-key), and in the empty state below (prominent) —
            // mirrored on iOS.
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionHeader("Holdings")
                Spacer(Modifier.weight(1f))
                if (model.holdings.isNotEmpty()) {
                    if (model.funding) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        TextButton(onClick = { model.getTestFundsAsync() }) {
                            Text("Get test funds", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
        if (model.holdings.isEmpty()) {
            item {
                ListItem(
                    headlineContent = {
                        Text("No holdings yet — receive CC to get started.")
                    },
                    supportingContent = {
                        // Dev-network faucet (LocalNet/DevNet): lets an empty
                        // wallet fund itself. See WalletModel.getTestFunds.
                        Button(
                            onClick = { model.getTestFundsAsync() },
                            enabled = !model.funding,
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            if (model.funding) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.size(8.dp))
                            }
                            Text(if (model.funding) "Getting test funds…" else "Get test funds")
                        }
                    },
                )
            }
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
                    holdings = group,
                )
            }
            .sortedWith(compareBy({ it.locked }, { it.label }))
        items(groups, key = { "${it.label}|${it.locked}" }) { group ->
            ListItem(
                modifier = Modifier.clickable { selectedGroup = group },
                headlineContent = {
                    Text("${group.amount.cc()} ${group.label}")
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
        model.lastError?.let { error ->
            item {
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
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
    val holdings: List<Holding>,
)

/** One backing holding contract (Amulet): amount plus shortened contract id;
 *  tapping the row reveals the full id, selectable for copying. */
@Composable
private fun HoldingContractRow(holding: Holding, label: String) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("${holding.amount.cc()} $label")
                Text(
                    holding.contractId.take(24) + "…",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription =
                    if (expanded) "Hide full contract id" else "Show full contract id",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            SelectionContainer {
                Text(
                    holding.contractId,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InboxScreen(model: WalletModel) {
    var refreshing by remember { mutableStateOf(false) }
    val refreshScope = rememberCoroutineScope()
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            refreshScope.launch {
                refreshing = true
                model.refresh()
                refreshing = false
            }
        },
    ) {
    LazyColumn {
        if (model.inbox.isEmpty()) {
            item { ListItem(headlineContent = { Text("No pending offers.") }) }
        }
        items(model.inbox, key = { it.contractId }) { offer ->
            ListItem(
                headlineContent = {
                    Text("${offer.transfer.amount.cc()} ${offer.transfer.instrumentId.id}")
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
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error,
                                ),
                            ) { Text("Reject") }
                        }
                    }
                },
            )
            HorizontalDivider()
        }
    }
    }
}

/** Send and Receive, paged by a segmented control at the top; hosted in the
 *  Portfolio sheet. A `canton-checkout:` deep link lands on Send. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransferScreen(model: WalletModel, initialPage: TransferPage = TransferPage.Send) {
    var page by remember { mutableStateOf(initialPage) }
    // A scanned/deep-linked checkout is a payment — show Send.
    LaunchedEffect(model.pendingCheckoutUrl) {
        if (model.pendingCheckoutUrl != null) page = TransferPage.Send
    }
    Column(Modifier.fillMaxSize()) {
        SingleChoiceSegmentedButtonRow(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            TransferPage.entries.forEachIndexed { index, item ->
                SegmentedButton(
                    selected = page == item,
                    onClick = { page = item },
                    shape = SegmentedButtonDefaults.itemShape(index, TransferPage.entries.size),
                ) { Text(item.label) }
            }
        }
        when (page) {
            TransferPage.Send -> SendScreen(model)
            TransferPage.Receive -> ReceiveScreen(model)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SendScreen(model: WalletModel) {
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
                ReceiptRow("Amount", "${receipt.amount.cc()} CC")
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
    // Set when a `canton-checkout:` QR is scanned — the dApp order being paid.
    var checkoutNote by remember { mutableStateOf<String?>(null) }
    val scanScope = rememberCoroutineScope()

    // Prefill from a canton-checkout payload — shared by the in-app scanner and
    // a deep link. The hybrid `canton-checkout://pay?…` form carries the fields
    // inline (prefill instantly, no fetch, no server ping); the older pointer
    // form `canton-checkout:<url>` is fetched.
    val applyCheckout: (String) -> Unit = { raw ->
        val uri = android.net.Uri.parse(raw)
        val inlineTo = uri.getQueryParameter("to")
        if (inlineTo != null) {
            receiver = inlineTo
            amount = uri.getQueryParameter("amount") ?: ""
            memo = uri.getQueryParameter("memo") ?: ""
            checkoutNote = listOfNotNull(
                uri.getQueryParameter("shop")?.ifBlank { null },
                uri.getQueryParameter("item"),
            ).joinToString(" · ")
        } else {
            scanScope.launch {
                val info = model.fetchCheckout(raw.removePrefix("canton-checkout:"))
                if (info != null) {
                    receiver = info.payTo
                    amount = info.amount
                    memo = info.memo
                    checkoutNote = listOfNotNull(info.shop.ifBlank { null }, info.item).joinToString(" · ")
                } else {
                    checkoutNote = "Couldn't load that checkout."
                }
            }
        }
    }

    // A checkout deep link, delivered by MainActivity, prefills here.
    LaunchedEffect(model.pendingCheckoutUrl) {
        model.pendingCheckoutUrl?.let { url ->
            applyCheckout(url)
            model.clearPendingCheckout()
        }
    }

    // Prewarm/refresh the cached fee config as the amount changes; the
    // estimate itself recomputes from cache — no network call per keystroke.
    LaunchedEffect(amount) { model.ensureFeePreviewFresh() }

    // Clear the form once a send succeeds (the receipt sheet appears). Kept
    // until then so a failed send leaves the inputs to retry.
    LaunchedEffect(model.lastSend) {
        if (model.lastSend != null) {
            receiver = ""
            amount = ""
            memo = ""
            checkoutNote = null
        }
    }

    Column(
        Modifier.padding(16.dp).verticalScroll(rememberScrollState()).imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val context = LocalContext.current
        checkoutNote?.let { note ->
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.fillMaxWidth().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        "Reviewing checkout",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(note, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        OutlinedTextField(
            value = receiver,
            onValueChange = { receiver = it },
            label = { Text("Recipient party id") },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
            ),
            trailingIcon = {
                IconButton(onClick = {
                    val options = GmsBarcodeScannerOptions.Builder()
                        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                        .build()
                    GmsBarcodeScanning.getClient(context, options).startScan()
                        .addOnSuccessListener { code ->
                            code.rawValue?.let { raw ->
                                val scanned = raw.trim()
                                if (scanned.startsWith("canton-checkout:")) {
                                    // A dApp checkout QR: prefill (inline) or fetch, then review.
                                    applyCheckout(scanned)
                                } else {
                                    receiver = scanned
                                    checkoutNote = null
                                }
                            }
                        }
                        .addOnFailureListener {
                            Log.i("WALLET", "scan failed: $it")
                            android.widget.Toast.makeText(
                                context,
                                "Scanner unavailable — paste the party id instead.",
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }
                }) { Icon(Icons.Outlined.QrCodeScanner, contentDescription = "Scan QR code") }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = amount,
            onValueChange = { amount = it },
            label = { Text("Amount (CC)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            supportingText = {
                Column {
                    Row {
                        Text("Available: ${model.totalAmulet.cc()} CC")
                        Spacer(Modifier.size(12.dp))
                        Text(
                            "MAX",
                            modifier = androidx.compose.ui.Modifier.clickable {
                                amount = model.totalAmulet.stripTrailingZeros().toPlainString()
                            },
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    // Appears once the amount parses positive and the cached
                    // scan config is available; absent otherwise. 0 CC on
                    // today's networks (CIP-0078) — see
                    // WalletModel.estimatedFee for the honesty note.
                    amount.toBigDecimalOrNull()?.let { model.estimatedFee(it) }?.let { fee ->
                        Text("Network fee: ${fee.cc()} CC")
                    }
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
        Modifier.fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().padding(20.dp),
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
                SelectionContainer {
                    Text(party, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
                val clipboard = LocalClipboardManager.current
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(party)) }) {
                    Text("Copy party id")
                }
                Text(
                    "Senders create a transfer to this party; it arrives in your Inbox to accept — or instantly with preapproval below.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        SectionHeader("Receive instantly", Modifier.padding(top = 8.dp))
        val waiting = model.preapprovalRequested && model.preapproval == null
        ListItem(
            headlineContent = { Text("Instant receiving") },
            supportingContent = {
                Column {
                    Text(
                        when {
                            model.preapproval != null ->
                                "Transfers to you settle in one step — no acceptance needed. " +
                                    "Turning this off archives the preapproval, signed on-device."
                            waiting -> "Waiting for your validator to approve…"
                            else -> "Asks your validator to preapprove incoming transfers, so they " +
                                "settle without an inbox step. Signed on-device."
                        }
                    )
                    model.preapproval?.expiresAt?.let {
                        Text(
                            "Renews " + java.time.format.DateTimeFormatter
                                .ofLocalizedDate(java.time.format.FormatStyle.MEDIUM)
                                .withZone(java.time.ZoneId.systemDefault())
                                .format(it),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            trailingContent = {
                if (waiting) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                } else {
                    Switch(
                        checked = model.preapproval != null,
                        onCheckedChange = { on ->
                            if (on) model.requestInstantReceiveAsync()
                            else model.cancelInstantReceiveAsync()
                        },
                        enabled = !model.busy,
                    )
                }
            },
        )
    }
}

/** One display label per history row and detail — from the SDK's transfer
 *  summary when present, from the raw created/archived deltas otherwise.
 *  UNKNOWN with a positive net is how taps and preapproved direct receives
 *  surface (no transfer view), so it reads "Received" — but the row never
 *  invents a counterparty for it. */
private fun changeTitle(change: TokenStandardClient.HoldingsChange): String {
    val summary = change.summary
        ?: return if (change.created.isEmpty()) "Sent / spent" else "Received"
    return when (summary.direction) {
        TransferDirection.SENT -> "Sent"
        TransferDirection.RECEIVED -> "Received"
        TransferDirection.SELF_TRANSFER -> "Sent to self"
        TransferDirection.INTERNAL -> "Internal"
        TransferDirection.UNKNOWN ->
            if (summary.amount.signum() > 0) "Received" else "Activity"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransferDetailSheet(change: TokenStandardClient.HoldingsChange, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
            Column(
                Modifier.fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, bottom = 32.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(changeTitle(change), style = MaterialTheme.typography.titleLarge)
                SectionHeader("When")
                Text(
                    java.time.format.DateTimeFormatter
                        .ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM)
                        .withZone(java.time.ZoneId.systemDefault())
                        .format(change.recordTime),
                )
                change.summary?.let { summary ->
                    SectionHeader("Transfer")
                    ReceiptRow("Direction", changeTitle(change))
                    summary.counterparty?.let { party ->
                        Text("Counterparty", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SelectionContainer {
                            Text(
                                party,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    summary.memo?.takeIf { it.isNotBlank() }?.let { ReceiptRow("Memo", it) }
                }
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
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) { Text("Done") }
            }
    }
}

/** One transfer row; shared by the merged Activity feed and its Transfers
 *  filter. */
@Composable
private fun TransferRow(change: TokenStandardClient.HoldingsChange, onClick: () -> Unit) {
            val summary = change.summary
            val received =
                if (summary != null) {
                    summary.amount.signum() > 0 &&
                        (summary.direction == TransferDirection.RECEIVED ||
                            summary.direction == TransferDirection.UNKNOWN)
                } else {
                    change.created.isNotEmpty()
                }
            val icon = when {
                summary?.direction == TransferDirection.INTERNAL -> Icons.Outlined.Autorenew
                summary?.direction == TransferDirection.UNKNOWN && !received ->
                    Icons.Outlined.SwapHoriz
                received -> Icons.AutoMirrored.Outlined.CallReceived
                else -> Icons.AutoMirrored.Outlined.CallMade
            }
            ListItem(
                modifier = Modifier.clickable(onClick = onClick),
                leadingContent = {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = if (received) Color(0xFF2E7D32)
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                headlineContent = { Text(changeTitle(change)) },
                supportingContent = {
                    Column {
                        if (summary != null) {
                            summary.counterparty?.let { party ->
                                Text(
                                    (if (summary.direction == TransferDirection.SENT) "to " else "from ") +
                                        party.take(30) + "…",
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            summary.memo?.takeIf { it.isNotBlank() }?.let {
                                Text("“$it”", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Text(
                            DateUtils.getRelativeTimeSpanString(change.recordTime.toEpochMilli()).toString(),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
                trailingContent = {
                    if (summary != null) {
                        Text(
                            (if (summary.amount.signum() > 0) "+" else "") +
                                "${summary.amount.cc()} CC",
                            color = if (received) Color(0xFF2E7D32) else Color.Unspecified,
                        )
                    } else {
                        val credited = change.created.fold(java.math.BigDecimal.ZERO) { acc, holding ->
                            acc + holding.amount
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            if (credited.signum() > 0) {
                                Text(
                                    "+${credited.cc()} CC",
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
                    }
                },
            )
            HorizontalDivider()
}

/** One agent-activity row: what a connected dApp did or tried, sheet or no
 *  sheet. The silent kinds carry their own tints so refusals and
 *  auto-approvals read at a glance. */
@Composable
private fun AgentActivityRow(activity: io.github.vsima.canton.dapp.wallet.DappActivity) {
    val kind = activity.kind
    val amount = activity.transfer?.let {
        "${it.amount} ${if (it.instrumentId == "Amulet") "CC" else it.instrumentId}"
    }
    val (icon, tint, title) = when (kind) {
        io.github.vsima.canton.dapp.wallet.DappActivity.Kind.CONNECTED ->
            Triple(Icons.Outlined.Link, MaterialTheme.colorScheme.primary, "Connected")
        io.github.vsima.canton.dapp.wallet.DappActivity.Kind.CONNECTION_DECLINED ->
            Triple(Icons.Outlined.Link, MaterialTheme.colorScheme.onSurfaceVariant, "Connection declined")
        io.github.vsima.canton.dapp.wallet.DappActivity.Kind.MESSAGE_SIGNED ->
            Triple(Icons.Outlined.Key, MaterialTheme.colorScheme.tertiary, "Signed in")
        io.github.vsima.canton.dapp.wallet.DappActivity.Kind.MESSAGE_DECLINED ->
            Triple(Icons.Outlined.Key, MaterialTheme.colorScheme.onSurfaceVariant, "Sign-in declined")
        io.github.vsima.canton.dapp.wallet.DappActivity.Kind.TRANSACTION_REQUESTED ->
            Triple(Icons.Outlined.Payments, MaterialTheme.colorScheme.onSurfaceVariant, "Payment requested")
        io.github.vsima.canton.dapp.wallet.DappActivity.Kind.TRANSACTION_AUTO_APPROVED ->
            Triple(Icons.Outlined.Payments, Color(0xFF2E7D32), "Auto-approved" + (amount?.let { ": $it" } ?: ""))
        io.github.vsima.canton.dapp.wallet.DappActivity.Kind.TRANSACTION_REFUSED ->
            Triple(Icons.Outlined.Payments, MaterialTheme.colorScheme.error, "Refused by your policy")
        io.github.vsima.canton.dapp.wallet.DappActivity.Kind.TRANSACTION_RATE_LIMITED ->
            Triple(Icons.Outlined.Payments, MaterialTheme.colorScheme.error, "Rate-limited")
        io.github.vsima.canton.dapp.wallet.DappActivity.Kind.TRANSACTION_DECLINED ->
            Triple(Icons.Outlined.Payments, MaterialTheme.colorScheme.onSurfaceVariant, "Payment declined")
        io.github.vsima.canton.dapp.wallet.DappActivity.Kind.TRANSACTION_EXECUTED ->
            Triple(Icons.Outlined.Payments, Color(0xFF2E7D32), "Paid" + (amount?.let { " $it" } ?: ""))
        io.github.vsima.canton.dapp.wallet.DappActivity.Kind.TRANSACTION_FAILED ->
            Triple(Icons.Outlined.Payments, MaterialTheme.colorScheme.error, "Payment failed")
    }
    ListItem(
        leadingContent = { Icon(icon, contentDescription = null, tint = tint) },
        headlineContent = { Text(title) },
        supportingContent = {
            Column {
                Text(
                    activity.peerName,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                )
                activity.transfer?.let { t ->
                    if (kind != io.github.vsima.canton.dapp.wallet.DappActivity.Kind.TRANSACTION_EXECUTED &&
                        kind != io.github.vsima.canton.dapp.wallet.DappActivity.Kind.TRANSACTION_AUTO_APPROVED
                    ) {
                        Text(
                            "${t.amount} ${if (t.instrumentId == "Amulet") "CC" else t.instrumentId} " +
                                "to ${t.receiver.take(24)}…",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        Text(
                            "to ${t.receiver.take(30)}…",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                activity.detail?.takeIf {
                    kind != io.github.vsima.canton.dapp.wallet.DappActivity.Kind.TRANSACTION_EXECUTED &&
                        kind != io.github.vsima.canton.dapp.wallet.DappActivity.Kind.CONNECTED
                }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    DateUtils.getRelativeTimeSpanString(activity.at.toEpochMilli()).toString(),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        },
    )
    HorizontalDivider()
}

/**
 * The Activity tab: one feed for everything that happened in the wallet.
 * Requests (the old Inbox) stay actionable, transfers (the old History)
 * keep their detail sheets, and agent events, including the spend policy's
 * sheetless outcomes, appear inline. Opening the tab clears the badge.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActivityScreen(model: WalletModel) {
    var filter by remember { mutableStateOf(ActivityFilter.All) }
    var selected by remember { mutableStateOf<TokenStandardClient.HoldingsChange?>(null) }
    LaunchedEffect(Unit) { model.markAgentActivitySeen() }
    selected?.let { TransferDetailSheet(it) { selected = null } }

    Column(Modifier.fillMaxSize()) {
        SingleChoiceSegmentedButtonRow(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            ActivityFilter.entries.forEachIndexed { index, item ->
                SegmentedButton(
                    selected = filter == item,
                    onClick = { filter = item },
                    shape = SegmentedButtonDefaults.itemShape(index, ActivityFilter.entries.size),
                ) { Text(item.label) }
            }
        }
        when (filter) {
            ActivityFilter.Requests -> InboxScreen(model)
            else -> {
                var refreshing by remember { mutableStateOf(false) }
                val refreshScope = rememberCoroutineScope()
                PullToRefreshBox(
                    isRefreshing = refreshing,
                    onRefresh = {
                        refreshScope.launch {
                            refreshing = true
                            model.refresh()
                            refreshing = false
                        }
                    },
                ) {
                    val showTransfers = filter != ActivityFilter.Dapps
                    val showAgents = filter != ActivityFilter.Transfers
                    val transfers =
                        if (showTransfers) {
                            model.history.filter { it.summary?.amount?.signum() != 0 }
                        } else {
                            emptyList()
                        }
                    val agents = if (showAgents) model.agentActivity else emptyList()
                    // One reverse-chronological feed across both sources.
                    val feed: List<Any> = (
                        transfers.map { it.recordTime.toEpochMilli() to it as Any } +
                            agents.map { it.at.toEpochMilli() to it as Any }
                        ).sortedByDescending { it.first }.map { it.second }
                    LazyColumn {
                        if (filter == ActivityFilter.All && model.inbox.isNotEmpty()) {
                            item {
                                ListItem(
                                    modifier = Modifier.clickable { filter = ActivityFilter.Requests },
                                    leadingContent = {
                                        Icon(
                                            Icons.Outlined.Inbox,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    },
                                    headlineContent = {
                                        Text(
                                            "${model.inbox.size} pending request" +
                                                if (model.inbox.size == 1) "" else "s",
                                        )
                                    },
                                    supportingContent = { Text("Tap to review and accept or reject") },
                                )
                                HorizontalDivider()
                            }
                        }
                        if (feed.isEmpty()) {
                            item { ListItem(headlineContent = { Text("No activity yet.") }) }
                        }
                        items(feed.size) { index ->
                            when (val entry = feed[index]) {
                                is TokenStandardClient.HoldingsChange ->
                                    TransferRow(entry) { selected = entry }
                                is io.github.vsima.canton.dapp.wallet.DappActivity ->
                                    AgentActivityRow(entry)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun qrBitmap(text: String): Bitmap? = try {
    val size = 512
    // Quartile error correction: party ids are long, and phone-camera scans
    // of another device's screen need headroom for glare and angle.
    val matrix = QRCodeWriter().encode(
        text, BarcodeFormat.QR_CODE, size, size,
        mapOf(
            com.google.zxing.EncodeHintType.ERROR_CORRECTION to
                com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.Q
        ),
    )
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        for (x in 0 until size) {
            pixels[y * size + x] =
                if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
    }
    Bitmap.createBitmap(pixels, size, size, Bitmap.Config.RGB_565)
} catch (_: Exception) {
    null
}

/** One shared CC amount rendering: at least one, at most four fraction
 *  digits — matching the iOS formatter so both apps read the same. */
private val ccFormat = java.text.DecimalFormat("0.0###")
private fun java.math.BigDecimal.cc(): String = ccFormat.format(this)

/**
 * Connect a dApp over WalletConnect: scan or paste a `wc:` link. The wallet
 * pairs, then the dApp's connect and each signature surface as approval sheets
 * ([WcApprovalSheet]) — the key never leaves the device.
 */
@Composable
private fun ConnectScreen(model: WalletModel) {
    var uri by remember { mutableStateOf("") }
    var selectedDapp by remember { mutableStateOf<WcSessionInfo?>(null) }
    val context = LocalContext.current
    LaunchedEffect(Unit) { model.refreshWcSessions() }
    selectedDapp?.let { DappDetailSheet(model, it) { selectedDapp = null } }
    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()).imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Connect an agent or dApp", style = MaterialTheme.typography.titleMedium)
        Text(
            "Scan or paste a WalletConnect link (wc:…) shown by a dApp. You approve " +
                "sharing your account and approve each signature — the key never leaves this device.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = uri,
            onValueChange = { uri = it },
            label = { Text("WalletConnect URI (wc:…)") },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
            ),
            trailingIcon = {
                IconButton(onClick = {
                    val options = GmsBarcodeScannerOptions.Builder()
                        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                        .build()
                    GmsBarcodeScanning.getClient(context, options).startScan()
                        .addOnSuccessListener { code -> code.rawValue?.let { uri = it.trim() } }
                        .addOnFailureListener { Log.i("WALLET", "wc scan failed: $it") }
                }) { Icon(Icons.Outlined.QrCodeScanner, contentDescription = "Scan WalletConnect QR") }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                model.pairWalletConnect(uri)
                uri = ""
            },
            enabled = uri.trim().startsWith("wc:"),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Connect") }
        model.wcStatus?.let { status ->
            Text(
                status,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (model.wcSessions.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            SectionHeader("Connected dApps")
            Text(
                "Tap a dApp to set its spending limits and see what it has done.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            model.wcSessions.forEach { session ->
                ElevatedCard(
                    Modifier.fillMaxWidth().clickable { selectedDapp = session },
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(session.name, style = MaterialTheme.typography.bodyLarge)
                            if (session.url.isNotBlank()) {
                                Text(
                                    session.url,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                "session ${session.topic.take(10)}…",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(
                            onClick = { model.disconnectWcSession(session.topic) },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) { Text("Disconnect") }
                    }
                }
            }
        }
    }
}

/**
 * Per-dApp detail: the spend-policy editor and that peer's slice of the
 * activity feed. Policies take effect immediately; sessions read them fresh
 * on every request. Auto-approval is a per-dApp opt-in with the caps as its
 * hard bound, and the warning copy says exactly what it removes: the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DappDetailSheet(model: WalletModel, session: WcSessionInfo, onDismiss: () -> Unit) {
    val existing = remember(session.topic) { model.dappPolicy(session.topic) }
    var maxPerTx by remember { mutableStateOf(existing?.maxPerTransaction?.toPlainString() ?: "") }
    var dailyCap by remember { mutableStateOf(existing?.dailyCap?.toPlainString() ?: "") }
    var autoApprove by remember { mutableStateOf(existing?.autoApproveBelow != null) }
    var autoBelow by remember { mutableStateOf(existing?.autoApproveBelow?.toPlainString() ?: "") }
    var saved by remember { mutableStateOf(false) }

    fun parsed(text: String): java.math.BigDecimal? =
        text.trim().takeIf { it.isNotEmpty() }?.let {
            try {
                java.math.BigDecimal(it).takeIf { v -> v.signum() > 0 }
            } catch (_: NumberFormatException) {
                null
            }
        }
    val maxValid = maxPerTx.isBlank() || parsed(maxPerTx) != null
    val capValid = dailyCap.isBlank() || parsed(dailyCap) != null
    val autoValid = !autoApprove || parsed(autoBelow) != null

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(session.name, style = MaterialTheme.typography.titleLarge)
            if (session.url.isNotBlank()) {
                Text(
                    session.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionHeader("Spending limits")
            Text(
                "Hard limits this wallet enforces before anything reaches you. A request " +
                    "outside them is refused without asking; it still shows in Activity.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = maxPerTx,
                onValueChange = { maxPerTx = it; saved = false },
                label = { Text("Max per payment (CC)") },
                isError = !maxValid,
                supportingText = { Text("Empty = no cap") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = dailyCap,
                onValueChange = { dailyCap = it; saved = false },
                label = { Text("Daily cap, rolling 24h (CC)") },
                isError = !capValid,
                supportingText = { Text("Empty = no cap") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Auto-approve small payments")
                    Text(
                        "Payments at or under the amount below execute with no " +
                            "approval sheet. You get a notification and an Activity entry instead.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = autoApprove,
                    onCheckedChange = { autoApprove = it; saved = false },
                )
            }
            if (autoApprove) {
                OutlinedTextField(
                    value = autoBelow,
                    onValueChange = { autoBelow = it; saved = false },
                    label = { Text("Auto-approve at or under (CC)") },
                    isError = !autoValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Button(
                onClick = {
                    val policy = io.github.vsima.canton.dapp.wallet.DappSpendPolicy(
                        maxPerTransaction = parsed(maxPerTx),
                        dailyCap = parsed(dailyCap),
                        autoApproveBelow = if (autoApprove) parsed(autoBelow) else null,
                    )
                    val empty = policy.maxPerTransaction == null &&
                        policy.dailyCap == null && policy.autoApproveBelow == null
                    model.setDappPolicy(session.topic, if (empty) null else policy)
                    saved = true
                },
                enabled = maxValid && capValid && autoValid,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (saved) "Saved" else "Save limits") }

            val peerActivity = model.agentActivity.filter { it.peerId == session.topic }
            if (peerActivity.isNotEmpty()) {
                SectionHeader("Activity")
                // The sheet scrolls as one column; cap the inline list.
                peerActivity.take(20).forEach { AgentActivityRow(it) }
            }
        }
    }
}

/**
 * The approval sheet the engine raises for a WalletConnect request: sharing an
 * account (connect) or signing a message. Approve/Reject answers the suspended
 * [DappApprovalDelegate] in [WalletModel]; dismissing rejects.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WcApprovalSheet(model: WalletModel) {
    val approval = model.pendingApproval ?: return
    // Skip the half-expanded state so the whole approval (amount, party, actions)
    // is visible at once, and pad past the system navigation bar so the buttons
    // clear the gesture bar.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = { approval.resolve(DappApproval.Rejected("Dismissed")) },
        sheetState = sheetState,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (val request = approval.request) {
                is DappApprovalRequest.Connection -> {
                    ApprovalHeader(
                        icon = Icons.Outlined.Link,
                        tint = MaterialTheme.colorScheme.primary,
                        title = "Connect",
                        peer = request.peer,
                    )
                    Text(
                        "Wants to see your Canton account. Sign-ins and payments it asks for later " +
                            "each come back to this phone for approval.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    SectionHeader("Account")
                    SelectionContainer {
                        Text(
                            request.available.firstOrNull()?.partyId ?: "—",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    ApprovalFactRow("Network", request.network.networkId)
                    WcApprovalButtons(
                        approveLabel = "Connect",
                        onApprove = { approval.resolve(DappApproval.Approved(request.available)) },
                        onReject = { approval.resolve(DappApproval.Rejected("Declined")) },
                    )
                }
                is DappApprovalRequest.Message -> {
                    ApprovalHeader(
                        icon = Icons.Outlined.Key,
                        tint = MaterialTheme.colorScheme.tertiary,
                        title = "Sign in",
                        peer = request.peer,
                    )
                    Text(
                        "Asks you to sign the message below. Signing proves you control your party; " +
                            "it moves no funds.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    SectionHeader("Message")
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .heightIn(max = 180.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp),
                    ) {
                        SelectionContainer {
                            Text(
                                request.message,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    WcApprovalButtons(
                        approveLabel = "Sign",
                        onApprove = { approval.resolve(DappApproval.Approved()) },
                        onReject = { approval.resolve(DappApproval.Rejected("Declined")) },
                    )
                }
                is DappApprovalRequest.Transaction -> {
                    val transfer = remember(request) { DappCommandSummary.transferOf(request.submission) }
                    ApprovalHeader(
                        icon = Icons.Outlined.Payments,
                        tint = TransactionAccent,
                        title = if (transfer != null) "Payment request" else "Approve transaction",
                        peer = request.peer,
                    )
                    if (transfer != null) {
                        Text(
                            "${transfer.amount} ${if (transfer.instrumentId == "Amulet") "CC" else transfer.instrumentId}",
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        SectionHeader("To")
                        SelectionContainer {
                            Text(
                                transfer.receiver,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        transfer.memo?.let { memo ->
                            SectionHeader("Memo")
                            Text(memo, style = MaterialTheme.typography.bodyMedium)
                        }
                        ApprovalFactRow("From", request.actAs.partyId.take(24) + "…", mono = true)
                        ApprovalFactRow("Network", request.network.networkId)
                    } else {
                        // Not a token-standard transfer: never guess. Name each
                        // command and show the raw payload so what is on screen
                        // is exactly what was asked.
                        Text(
                            "Asks you to sign a transaction that is not a standard token transfer. " +
                                "Review its commands:",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        DappCommandSummary.describe(request.submission).forEach { line ->
                            Text("• $line", style = MaterialTheme.typography.bodyMedium)
                        }
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.medium)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .heightIn(max = 160.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(12.dp),
                        ) {
                            SelectionContainer {
                                Text(
                                    request.submission.commands.toString(),
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        ApprovalFactRow("Acting as", request.actAs.partyId.take(24) + "…", mono = true)
                        ApprovalFactRow("Network", request.network.networkId)
                    }
                    Text(
                        "Prepared on your participant. This phone re-verifies the transaction " +
                            "hash before your hardware key signs.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    WcApprovalButtons(
                        approveLabel = "Approve",
                        onApprove = { approval.resolve(DappApproval.Approved()) },
                        onReject = { approval.resolve(DappApproval.Rejected("Declined")) },
                    )
                }
            }
        }
    }
}

/** Accent for the sheet that moves funds; the brand orange, deliberately not
 *  a theme color so it reads the same in light and dark. */
private val TransactionAccent = Color(0xFFE8502F)

/**
 * The identity block every approval sheet leads with: what kind of request
 * (icon + title) and who is asking (peer name, origin, and whether the
 * transport could verify that identity). A WalletConnect peer names itself,
 * so an unverified name is shown as a claim, not an identity.
 */
@Composable
private fun ApprovalHeader(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    title: String,
    peer: DappPeer,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    peer.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                if (!peer.verified) {
                    Text(
                        "Unverified",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            peer.url?.let { url ->
                Text(
                    url.removePrefix("https://").removePrefix("http://"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** One label + value line of an approval sheet. */
@Composable
private fun ApprovalFactRow(label: String, value: String, mono: Boolean = false) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (mono) FontFamily.Monospace else null,
        )
    }
}

@Composable
private fun WcApprovalButtons(approveLabel: String, onApprove: () -> Unit, onReject: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedButton(
            onClick = onReject,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) { Text("Reject") }
        Button(onClick = onApprove, modifier = Modifier.weight(1f)) { Text(approveLabel) }
    }
}
