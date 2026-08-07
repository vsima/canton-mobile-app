// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import CoreImage.CIFilterBuiltins
import UIKit

enum QRCode {
    /// Party ids are long; quartile error correction keeps the code scannable
    /// at phone-screen sizes without ballooning module count.
    static func image(for string: String) -> UIImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(string.utf8)
        filter.correctionLevel = "Q"
        guard let output = filter.outputImage else { return nil }
        let scaled = output.transformed(by: CGAffineTransform(scaleX: 8, y: 8))
        guard let cgImage = CIContext().createCGImage(scaled, from: scaled.extent) else {
            return nil
        }
        return UIImage(cgImage: cgImage)
    }
}
