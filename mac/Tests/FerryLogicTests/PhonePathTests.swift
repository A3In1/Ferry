import FerryLogic
import Testing

@Test func joinsPaths() {
    #expect(phonePath("", "DCIM") == "DCIM")
    #expect(phonePath("DCIM", "Camera") == "DCIM/Camera")
    #expect(phonePath("/DCIM/", "a b.jpg") == "DCIM/a b.jpg")
    #expect(phonePath("DCIM", ".hidden") == "DCIM/.hidden")
}

@Test(arguments: ["", ".", "..", "a/b", "../x", "x\0"])
func refusesBadNames(name: String) {
    #expect(phonePath("DCIM", name) == nil)
}

@Test func normalizes() {
    #expect(normalizedPhonePath("") == "")
    #expect(normalizedPhonePath("/") == "")
    #expect(normalizedPhonePath("/DCIM//Camera/") == "DCIM/Camera")
    #expect(normalizedPhonePath("DCIM/../etc") == nil)
    #expect(normalizedPhonePath("./DCIM") == nil)
    #expect(phonePath("DCIM/..", "x") == nil)
}

@Test func parents() {
    #expect(parentPhonePath("") == "")
    #expect(parentPhonePath("DCIM") == "")
    #expect(parentPhonePath("DCIM/Camera") == "DCIM")
    #expect(parentPhonePath("a/../b") == "")
}

@Test func crumbs() {
    #expect(breadcrumbs("") == [])
    #expect(breadcrumbs("DCIM/Camera") == [Crumb(name: "DCIM", path: "DCIM"), Crumb(name: "Camera", path: "DCIM/Camera")])
    #expect(breadcrumbs("/Music/") == [Crumb(name: "Music", path: "Music")])
}

@Test func sortsFoldersFirst() {
    let items = [(false, "b.txt"), (true, "Zed"), (false, "img10"), (false, "img2"), (true, "alpha")]
    let sorted = items.sorted { folderFirst((dir: $0.0, name: $0.1), (dir: $1.0, name: $1.1)) }.map(\.1)
    #expect(sorted == ["alpha", "Zed", "b.txt", "img2", "img10"])
}
