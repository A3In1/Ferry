import FerryLogic
import Testing

@Test func webURLs() {
    #expect(webURL("https://example.com/a?b=1")?.host == "example.com")
    #expect(webURL("  HTTP://example.com \n") != nil)
    for bad in ["file:///etc/passwd", "javascript:alert(1)", "ftp://x.com", "https://", "example.com",
                "https://a.com b", "ssh://host", ""] {
        #expect(webURL(bad) == nil, "\(bad)")
    }
}

@Test func categoryIDs() {
    let a = categoryID([("reply", "Reply", true), ("read", "Mark read", false)])
    #expect(a == categoryID([("reply", "Reply", true), ("read", "Mark read", false)]))
    #expect(a.hasPrefix("p|"))
    #expect(a != categoryID([("reply", "Reply", false), ("read", "Mark read", false)]))
    #expect(a != categoryID([("read", "Mark read", false), ("reply", "Reply", true)]))
    #expect(categoryID([("a|b", "c", false)]) != categoryID([("a", "b|c", false)]))
    #expect(categoryID([]) != a)
}
