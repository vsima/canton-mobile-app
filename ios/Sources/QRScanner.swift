// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import SwiftUI
import VisionKit

/// Stock VisionKit scanner presented from the Send screen to fill the
/// recipient party id from another wallet's Receive QR.
struct QRScannerSheet: View {
    @Environment(\.dismiss) private var dismiss
    let onScan: (String) -> Void

    var body: some View {
        NavigationStack {
            Group {
                if DataScannerViewController.isSupported && DataScannerViewController.isAvailable {
                    QRDataScanner { code in
                        onScan(code)
                        dismiss()
                    }
                } else {
                    // Simulators have no camera pipeline for DataScanner.
                    ContentUnavailableView(
                        "Scanner unavailable",
                        systemImage: "qrcode.viewfinder",
                        description: Text("Camera scanning isn't available on this device. Paste the party id instead.")
                    )
                }
            }
            .navigationTitle("Scan QR code")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
    }
}

private struct QRDataScanner: UIViewControllerRepresentable {
    let onScan: (String) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onScan: onScan) }

    func makeUIViewController(context: Context) -> DataScannerViewController {
        let scanner = DataScannerViewController(
            recognizedDataTypes: [.barcode(symbologies: [.qr])],
            qualityLevel: .balanced,
            isHighlightingEnabled: true
        )
        scanner.delegate = context.coordinator
        return scanner
    }

    func updateUIViewController(_ scanner: DataScannerViewController, context: Context) {
        try? scanner.startScanning()
    }

    @MainActor
    final class Coordinator: NSObject, DataScannerViewControllerDelegate {
        let onScan: (String) -> Void
        init(onScan: @escaping (String) -> Void) { self.onScan = onScan }

        func dataScanner(
            _ scanner: DataScannerViewController,
            didAdd added: [RecognizedItem],
            allItems: [RecognizedItem]
        ) {
            deliver(added)
        }

        func dataScanner(_ scanner: DataScannerViewController, didTapOn item: RecognizedItem) {
            deliver([item])
        }

        private func deliver(_ items: [RecognizedItem]) {
            for item in items {
                if case .barcode(let barcode) = item, let value = barcode.payloadStringValue {
                    onScan(value.trimmingCharacters(in: .whitespacesAndNewlines))
                    return
                }
            }
        }
    }
}
