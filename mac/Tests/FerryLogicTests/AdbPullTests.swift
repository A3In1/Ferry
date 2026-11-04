import FerryLogic
import Foundation
import Testing

@Suite struct AdbPullTests {
    @Test func mapsPhonePathsToAdbPaths() {
        #expect(adbDevicePath("DCIM/Camera") == "/storage/emulated/0/DCIM/Camera")
        #expect(adbDevicePath("/Download//a b.pdf/") == "/storage/emulated/0/Download/a b.pdf")
        #expect(adbDevicePath("") == nil) // the whole storage isn't a download
        #expect(adbDevicePath("DCIM/../..") == nil)
    }

    @Test func parsesModernProgress() {
        let p = AdbProgress.parse("\r[ 45%] /storage/emulated/0/DCIM/Camera/IMG_1.jpg: 80%\u{1B}[K")
        #expect(p == AdbProgress(overall: 45, path: "/storage/emulated/0/DCIM/Camera/IMG_1.jpg", filePercent: 80))
        #expect(p?.fileName == "IMG_1.jpg")
        #expect(AdbProgress.parse("[100%] /storage/emulated/0/a.txt: 100%")?.overall == 100)
    }

    @Test func parsesOlderAndUnknownSizes() {
        #expect(AdbProgress.parse("[  7%] /storage/emulated/0/Movies/clip.mp4")
            == AdbProgress(overall: 7, path: "/storage/emulated/0/Movies/clip.mp4", filePercent: nil))
        #expect(AdbProgress.parse("[   ?] /storage/emulated/0/x: 1234/?")
            == AdbProgress(overall: nil, path: "/storage/emulated/0/x", filePercent: nil))
        // a ": " inside the name stays part of it
        #expect(AdbProgress.parse("[ 10%] /storage/emulated/0/a: b.txt: 50%")?.path == "/storage/emulated/0/a: b.txt")
    }

    @Test func ignoresOtherLines() {
        #expect(AdbProgress.parse("/storage/emulated/0/DCIM/: 30 files pulled, 0 skipped. 38.1 MB/s (123 bytes in 0.003s)") == nil)
        #expect(AdbProgress.parse("adb: error: failed to stat remote object '/storage/emulated/0/x': No such file or directory") == nil)
        #expect(AdbProgress.parse("[abc] x") == nil)
        #expect(AdbProgress.parse("") == nil)
    }

    @Test func findsErrors() {
        let out = "[ 1%] x\r\nadb: error: failed to stat remote object '/storage/emulated/0/Android/data': Permission denied\n"
        #expect(adbPullError(out)?.hasSuffix("Permission denied") == true)
        #expect(adbPullError("x: 1 file pulled, 0 skipped.") == nil)
    }

    @Test func freeNamesNumberBeforeTheExtension() {
        let taken: Set = ["DCIM", "DCIM (1)", "a.jpg", ".bashrc"]
        #expect(freeName("DCIM", isDirectory: true) { taken.contains($0) } == "DCIM (2)")
        #expect(freeName("a.jpg", isDirectory: false) { taken.contains($0) } == "a (1).jpg")
        #expect(freeName(".bashrc", isDirectory: false) { taken.contains($0) } == ".bashrc (1)")
        #expect(freeName("my.folder", isDirectory: true) { _ in false } == "my.folder")
        #expect(freeName("v1.2", isDirectory: true) { $0 == "v1.2" } == "v1.2 (1)")
    }
}

@Suite struct UsbPullStateTests {
    let t0 = Date(timeIntervalSince1970: 1_000_000)

    @Test func folderCountsFilesAndUsesAdbPercent() {
        var s = UsbPullState(folder: true, knownSize: nil)
        #expect(s.fraction == nil && s.fileLine == nil)
        s.update(AdbProgress(overall: 10, path: "/storage/emulated/0/DCIM/a.jpg", filePercent: 50))
        s.update(AdbProgress(overall: 20, path: "/storage/emulated/0/DCIM/a.jpg", filePercent: 100))
        s.update(AdbProgress(overall: 50, path: "/storage/emulated/0/DCIM/b.jpg", filePercent: 10))
        #expect(s.fraction == 0.5)
        #expect(s.fileLine == "File 2: b.jpg")
        s.onDisk(10_000_000, at: t0)
        s.onDisk(20_000_000, at: t0 + 1)
        #expect(s.detail(at: t0 + 1) == "20 MB · 10.0 MB/s · 2 s left · over USB")
    }

    @Test func singleFileFallsBackToItsSize() {
        var s = UsbPullState(folder: false, knownSize: 100_000_000)
        s.onDisk(25_000_000, at: t0)
        #expect(s.fraction == 0.25)
        #expect(s.fileLine == nil)
        #expect(s.detail(at: t0) == "25 MB of 100 MB · over USB")
    }
}
