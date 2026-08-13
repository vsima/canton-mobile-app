// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import SwiftUI
import UIKit

/// Installs an app-wide "tap outside a text field to dismiss the keyboard"
/// gesture on the key window. Attach once near the root (`.background`).
///
/// The gesture is added to the window, not a specific view, so it covers every
/// screen with text entry (Connect, Transfer/Send). `cancelsTouchesInView =
/// false` keeps buttons, list rows, and the segmented control fully tappable,
/// and the delegate ignores taps that land on a text field or control so those
/// still focus normally.
struct KeyboardDismisser: UIViewRepresentable {
    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> UIView {
        let probe = UIView()
        // The window isn't attached until the view is in the hierarchy; defer
        // to the next runloop so `probe.window` is available.
        DispatchQueue.main.async {
            guard let window = probe.window else { return }
            let already = window.gestureRecognizers?.contains { $0.name == Coordinator.gestureName }
            if already == true { return }
            let tap = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.dismiss))
            tap.name = Coordinator.gestureName
            tap.cancelsTouchesInView = false
            tap.delegate = context.coordinator
            window.addGestureRecognizer(tap)
        }
        return probe
    }

    func updateUIView(_ uiView: UIView, context: Context) {}

    final class Coordinator: NSObject, UIGestureRecognizerDelegate {
        static let gestureName = "tap-to-dismiss-keyboard"

        @objc func dismiss() {
            UIApplication.shared.sendAction(
                #selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil
            )
        }

        /// Don't fire on taps that land on a text field or control — those need
        /// the touch to focus / activate. Everything else dismisses.
        func gestureRecognizer(_ recognizer: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool {
            var view = touch.view
            while let current = view {
                if current is UIControl || current is UITextField || current is UITextView { return false }
                view = current.superview
            }
            return true
        }

        func gestureRecognizer(
            _ recognizer: UIGestureRecognizer,
            shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer
        ) -> Bool { true }
    }
}
