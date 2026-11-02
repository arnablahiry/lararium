// Touch ID for the Mac app: the JVM can't reach LocalAuthentication, so this tiny helper (built by
// .github/workflows/build-mac.yml into the app's resources, named "Lararium" so the prompt says
// "Lararium is trying to unlock your vault") does it.
//   Lararium --check   exit 0 when Touch ID is set up on this Mac
//   Lararium           shows the Touch ID prompt: exit 0 when your finger matched
import Foundation
import LocalAuthentication

let ctx = LAContext()
var err: NSError?
let can = ctx.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &err)
if CommandLine.arguments.contains("--check") { exit(can ? 0 : 1) }
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
