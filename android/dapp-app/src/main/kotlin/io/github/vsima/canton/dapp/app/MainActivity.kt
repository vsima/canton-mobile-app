// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton.dapp.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.vsima.canton.dapp.DappClient
import io.github.vsima.canton.dapp.lan.LanGrpcDappTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The CIP-0103 **dApp** reference — deliberately links only `canton-dapp` and
 * the LAN transport, so it has no way to touch a signing driver or the Ledger
 * API stubs. That the R8 release build succeeds from these dependencies alone
 * is the demonstration that the module split holds.
 *
 * This is the shell the ping and sign-in screens land in. It dials a wallet
 * over the LAN and reports the connection result; until a wallet app is
 * running the LAN provider (next step), Connect simply reports that nothing
 * answered — which is the honest state of a dApp with no wallet to talk to.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { ConnectScreen() }
            }
        }
    }
}

@Composable
private fun ConnectScreen() {
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf("10.0.2.2") }
    var port by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Enter the wallet's LAN host and port, then Connect.") }
    var busy by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Canton dApp reference", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text("Wallet host") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = port,
            onValueChange = { port = it.filter(Char::isDigit) },
            label = { Text("Wallet port") },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            enabled = !busy && port.toIntOrNull() != null,
            onClick = {
                val p = port.toIntOrNull() ?: return@Button
                busy = true
                status = "Connecting to $host:$p…"
                scope.launch {
                    status = withContext(Dispatchers.IO) { attemptConnect(host, p) }
                    busy = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (busy) "Connecting…" else "Connect") }
        Text(status, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Builds a real DappClient over the LAN transport and reports the result. */
private suspend fun attemptConnect(host: String, port: Int): String {
    val transport = LanGrpcDappTransport(host, port)
    return try {
        val client = DappClient(transport)
        val result = client.connect()
        if (result.isConnected) {
            "Connected. ${client.listAccounts().size} account(s) shared."
        } else {
            "The wallet declined: ${result.reason ?: "no reason given"}"
        }
    } catch (e: Exception) {
        "Could not reach a wallet at $host:$port — ${e.message}"
    } finally {
        transport.close()
    }
}