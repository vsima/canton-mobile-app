// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import CantonDappWalletKit
import Foundation
import UserNotifications

/// Local notifications for the sheetless agent outcomes. A policy that
/// refuses or auto-approves without a sheet is only honest if the phone
/// still tells its owner at the moment it happens; the Activity tab is the
/// durable record, this is the tap on the shoulder. iOS twin of Android's
/// `AgentNotifications`.
enum AgentNotifications {
    /// Asked once at launch: the spend policy's silent outcomes surface here.
    static func requestAuthorization() async {
        _ = try? await UNUserNotificationCenter.current()
            .requestAuthorization(options: [.alert, .sound, .badge])
    }

    static func notify(_ activity: DappActivity) {
        let amount = activity.transfer.map {
            "\($0.amount) \($0.instrumentId == "Amulet" ? "CC" : $0.instrumentId)"
        }
        let title: String
        let body: String
        switch activity.kind {
        case .transactionAutoApproved:
            title = "\(activity.peerName) paid \(amount ?? "a transfer")"
            body = "Auto-approved by your spend policy. Open Activity for details."
        case .transactionRefused:
            title = "\(activity.peerName) was refused"
            body = activity.detail ?? "Outside your spend policy."
        case .transactionRateLimited:
            title = "\(activity.peerName) was rate-limited"
            body = activity.detail ?? "Too many requests."
        default:
            return
        }
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        let request = UNNotificationRequest(
            identifier: "agent-\(Int64(activity.at.timeIntervalSince1970 * 1000))",
            content: content,
            trigger: nil
        )
        UNUserNotificationCenter.current().add(request)
    }
}
