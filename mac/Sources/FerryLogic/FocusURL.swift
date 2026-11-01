import Foundation

/// The Do Not Disturb state in `ferry://focus?on=1` (or `on=0`), the link the user's Shortcuts
/// Focus automation opens; nil for any other URL.
public func focusState(from url: URL) -> Bool? {
    guard url.scheme == "ferry", url.host == "focus",
          let on = URLComponents(url: url, resolvingAgainstBaseURL: false)?
              .queryItems?.first(where: { $0.name == "on" })?.value
    else { return nil }
    switch on.lowercased() {
    case "1", "true", "on": return true
    case "0", "false", "off": return false
    default: return nil
    }
}
