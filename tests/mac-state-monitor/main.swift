import Foundation
import Darwin

let root=FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
try FileManager.default.createDirectory(at:root,withIntermediateDirectories:true)
defer { try? FileManager.default.removeItem(at:root) }
let state=root.appendingPathComponent("state.json")
try Data("initial".utf8).write(to:state,options:.atomic)
var observed=""
var changes=0
let monitor=try StateDirectoryMonitor(directory:root) {
    observed=(try? String(contentsOf:state,encoding:.utf8)) ?? ""
    changes += 1
}
func waitFor(_ condition: () -> Bool) {
    let deadline=Date().addingTimeInterval(3)
    while !condition() && Date()<deadline {
        _=RunLoop.main.run(mode:.default,before:Date().addingTimeInterval(0.01))
    }
    check(condition(),"Directory monitor missed an atomic replacement")
}
func check(_ condition: Bool, _ message: String) {
    if !condition { fputs("FAIL: \(message)\n",stderr); exit(1) }
}
func drain(for seconds: TimeInterval) {
    let deadline=Date().addingTimeInterval(seconds)
    while Date()<deadline { _=RunLoop.main.run(mode:.default,before:deadline) }
}
// A file-inode watcher would stop observing after the first replacement.
for value in ["first replacement","second replacement","third replacement"] {
    try Data(value.utf8).write(to:state,options:.atomic)
    waitFor { observed==value }
}
// Allow already queued directory events to drain before measuring idle behavior.
drain(for:0.1)
let count=changes
drain(for:0.2)
check(changes==count,"Idle monitor should not generate polling events")
monitor.cancel()
drain(for:0.05)
try Data("after cancellation".utf8).write(to:state,options:.atomic)
drain(for:0.2)
check(changes==count,"Cancelled monitor should not refresh the UI")
print("PASS: successive atomic replacements, no idle callbacks, cancellation")
