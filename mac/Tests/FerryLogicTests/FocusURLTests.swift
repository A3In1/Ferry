import FerryLogic
import Foundation
import Testing

@Test func focusURLs() {
    let state = { focusState(from: URL(string: $0)!) }
    #expect(state("ferry://focus?on=1") == true)
    #expect(state("ferry://focus?on=off") == false)
    #expect(state("ferry://focus?on=maybe") == nil)
    #expect(state("ferry://focus") == nil)
    #expect(state("ferry://pair?id=x&on=1") == nil)
    #expect(state("https://focus?on=1") == nil)
}
