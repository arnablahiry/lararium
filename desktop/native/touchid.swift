// The Mac app's small native helper (the JVM can't reach these macOS frameworks), built by
// .github/workflows/build-mac.yml into the app's resources and named "Lararium", so the Touch ID prompt says
// "Lararium is trying to unlock your vault".
//   Lararium --check        exit 0 when Touch ID is set up on this Mac
//   Lararium                shows the Touch ID prompt: exit 0 when your finger matched
//   Lararium --ocr <image>  prints the text in the picture (Apple's Vision), one line per line of text
import AppKit
import Foundation
import LocalAuthentication
import Vision

let args = CommandLine.arguments

if args.count >= 3 && args[1] == "--ocr" {
    guard let img = NSImage(contentsOf: URL(fileURLWithPath: args[2])),
          let cg = img.cgImage(forProposedRect: nil, context: nil, hints: nil) else { exit(3) }
    let req = VNRecognizeTextRequest()
    req.recognitionLevel = .accurate
    req.usesLanguageCorrection = false // document numbers and the machine-readable lines are not words
    do { try VNImageRequestHandler(cgImage: cg, options: [:]).perform([req]) } catch { exit(4) }
    // top to bottom, then left to right (Vision's y grows upwards)
    let found = (req.results ?? []).compactMap { o -> (CGRect, String)? in o.topCandidates(1).first.map { (o.boundingBox, $0.string) } }
    let rows = found.sorted { a, b in abs(a.0.midY - b.0.midY) < 0.012 ? a.0.minX < b.0.minX : a.0.midY > b.0.midY }
    FileHandle.standardOutput.write((rows.map { $0.1 }.joined(separator: "\n") + "\n").data(using: .utf8)!)
    exit(0)
}

let ctx = LAContext()
var err: NSError?
let can = ctx.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &err)
if args.contains("--check") { exit(can ? 0 : 1) }
guard can else { exit(2) }
ctx.localizedCancelTitle = "Use passcode"
let done = DispatchSemaphore(value: 0)
var ok = false
ctx.evaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, localizedReason: "unlock your vault") { success, _ in
    ok = success
    done.signal()
}
done.wait()
exit(ok ? 0 : 1)
