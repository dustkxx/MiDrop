import Foundation
import Darwin

// Watch the directory because the receiver atomically replaces state.json.
// Watching the file itself would keep following the inode that was replaced.
final class StateDirectoryMonitor {
    private let source: DispatchSourceFileSystemObject

    init(directory: URL, onChange: @escaping () -> Void) throws {
        let fd = open(directory.path, O_EVTONLY | O_CLOEXEC)
        guard fd >= 0 else { throw POSIXError(POSIXErrorCode(rawValue: errno) ?? .EIO) }
        source = DispatchSource.makeFileSystemObjectSource(fileDescriptor: fd, eventMask: .write, queue: .main)
        source.setEventHandler(handler: onChange)
        source.setCancelHandler { close(fd) }
        source.resume()
    }

    func cancel() { source.cancel() }
    deinit { source.cancel() }
}
