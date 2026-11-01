import AppKit
import Sparkle

/// Sparkle auto-update: Check for Updates… in the menu, plus a daily background check (SUScheduledCheckInterval)
/// unless the user turns it off. Only runs from a bundle built with a feed (FERRY_FEED_URL in scripts/bundle-mac.sh);
/// otherwise Check for Updates… explains instead of Sparkle reporting a misconfigured app.
@MainActor final class Updates: NSObject {
    static let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "(development build)"

    /// The appcast baked in at bundle time. nil for `swift run` and for bundles built without FERRY_FEED_URL.
    let feed: URL? = {
        guard Bundle.main.bundleURL.pathExtension == "app",
              let text = Bundle.main.object(forInfoDictionaryKey: "SUFeedURL") as? String,
              let url = URL(string: text), url.scheme == "https", url.host() != nil else { return nil }
        return url
    }()

    /// A background check found this version and we're holding it in the menu (and a notification) instead of a window.
    private(set) var available: String?
    var onChange: () -> Void = {}

    private let model: AppModel
    private lazy var controller = SPUStandardUpdaterController(startingUpdater: false, updaterDelegate: nil, userDriverDelegate: self)
    private var observation: NSKeyValueObservation?

    init(model: AppModel) {
        self.model = model
        super.init()
        guard feed != nil else { return }
        controller.startUpdater()
        observation = controller.updater.observe(\.canCheckForUpdates) { [weak self] _, _ in
            MainActor.assumeIsolated { self?.onChange() } // Sparkle changes it on the main thread
        }
    }

    /// For the menu item's enabled state: false while a check or download is already showing.
    var canCheck: Bool { feed == nil || controller.updater.canCheckForUpdates }

    var automatic: Bool {
        get { feed != nil && controller.updater.automaticallyChecksForUpdates }
        set { controller.updater.automaticallyChecksForUpdates = newValue }
    }

    func check() {
        guard feed != nil else {
            model.showAlert("Updates aren't set up for this build yet",
                            "This copy of Ferry was built without an update feed, so it can't check for new versions. Ferry \(Self.version) is what you have.")
            return
        }
        NSApp.activate() // a menu-bar app isn't frontmost; without this Sparkle's window opens behind others
        controller.checkForUpdates(nil)
    }

    private func clearAvailable() {
        guard available != nil else { return }
        available = nil
        model.removeNotifications { $0.request.identifier == Notifications.update }
        onChange()
    }
}

/// Gentle reminders: a background check that finds an update shows it in the menu and a notification
/// rather than a window popping up behind whatever the user is doing.
extension Updates: @preconcurrency SPUStandardUserDriverDelegate {
    var supportsGentleScheduledUpdateReminders: Bool { true }

    func standardUserDriverShouldHandleShowingScheduledUpdate(_ update: SUAppcastItem, andInImmediateFocus immediateFocus: Bool) -> Bool {
        immediateFocus // e.g. found right at launch: Sparkle's window is fine then
    }

    func standardUserDriverWillHandleShowingUpdate(_ handleShowingUpdate: Bool, forUpdate update: SUAppcastItem, state: SPUUserUpdateState) {
        if handleShowingUpdate { return NSApp.activate() }
        available = update.displayVersionString
        model.notify("Ferry \(update.displayVersionString) is available", "Click to see what's new and install it.", id: Notifications.update)
        onChange()
    }

    func standardUserDriverDidReceiveUserAttention(forUpdate update: SUAppcastItem) { clearAvailable() }

    func standardUserDriverWillFinishUpdateSession() { clearAvailable() }

    func standardUserDriverWillShowModalAlert() { NSApp.activate() }
}
