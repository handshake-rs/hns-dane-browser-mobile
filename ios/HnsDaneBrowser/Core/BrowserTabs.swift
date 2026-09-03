import Foundation

/// Bounded, presentation-only tab metadata.
///
/// A tab deliberately does not own a WebView, proxy, cookie store, or native runtime session.
/// The active tab is the only tab allowed to acquire those capabilities.
struct BrowserTab: Equatable, Identifiable {
    private static let maximumDisplayTitleCharacters = 120
    private static let maximumDisplayAddressCharacters = 256

    let id: UInt64
    private(set) var address: String
    private(set) var title: String?

    var displayTitle: String {
        let value = title.flatMap { $0.isEmpty ? nil : $0 }
            ?? URL(string: address)?.host
            ?? address
        return Self.boundedDisplay(value, maximum: Self.maximumDisplayTitleCharacters)
    }

    var displayAddress: String {
        Self.boundedDisplay(
            BrowserAddressPresentation.displayText(for: address),
            maximum: Self.maximumDisplayAddressCharacters
        )
    }

    mutating func updateAddress(_ address: String) {
        guard BrowserTabs.isAllowedAddressMetadata(address) else { return }
        if self.address != address {
            self.address = address
            title = nil
        }
    }

    mutating func updateTitle(_ title: String?) {
        let normalized = title?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard !normalized.isEmpty else {
            self.title = nil
            return
        }
        // Bound work before removing control characters supplied by untrusted page content.
        let bounded = String(normalized.prefix(BrowserTabs.maximumTitleCharacters))
        let sanitized = bounded.components(separatedBy: .controlCharacters).joined(separator: " ")
        self.title = sanitized.isEmpty ? nil : sanitized
    }

    private static func boundedDisplay(_ value: String, maximum: Int) -> String {
        guard value.count > maximum else { return value }
        return String(value.prefix(maximum - 1)) + "…"
    }
}

struct BrowserTabs: Equatable {
    static let maximumCount = 8
    static let maximumAddressBytes = 16 * 1_024
    static let maximumTitleCharacters = 512

    enum CloseResult: Equatable {
        case rejected
        case closedInactive
        case selected(address: String)
    }

    struct Snapshot: Equatable {
        let tabs: [BrowserTab]
        let activeID: UInt64
    }

    private(set) var tabs: [BrowserTab]
    private(set) var activeID: UInt64
    private var nextID: UInt64

    init(homepage: String) {
        let address = Self.isAllowedAddressMetadata(homepage)
            ? homepage
            : BrowserSettingsPreferences.defaultHomepage
        let first = BrowserTab(id: 1, address: address, title: nil)
        tabs = [first]
        activeID = first.id
        nextID = 2
    }

    var snapshot: Snapshot {
        Snapshot(tabs: tabs, activeID: activeID)
    }

    var activeTab: BrowserTab {
        // The invariants below guarantee a match. Retaining the fallback makes this accessor
        // total even if a future decoder is added incorrectly.
        tabs.first(where: { $0.id == activeID }) ?? tabs[0]
    }

    @discardableResult
    mutating func open(homepage: String) -> BrowserTab? {
        guard tabs.count < Self.maximumCount else { return nil }
        let address = Self.isAllowedAddressMetadata(homepage)
            ? homepage
            : BrowserSettingsPreferences.defaultHomepage
        let tab = BrowserTab(id: allocateID(), address: address, title: nil)
        tabs.append(tab)
        activeID = tab.id
        return tab
    }

    @discardableResult
    mutating func select(id: UInt64) -> BrowserTab? {
        guard let tab = tabs.first(where: { $0.id == id }) else { return nil }
        activeID = id
        return tab
    }

    @discardableResult
    mutating func close(id: UInt64) -> CloseResult {
        guard tabs.count > 1,
              let index = tabs.firstIndex(where: { $0.id == id }) else {
            return .rejected
        }
        let wasActive = id == activeID
        tabs.remove(at: index)
        guard wasActive else { return .closedInactive }

        let selectedIndex = min(index, tabs.count - 1)
        activeID = tabs[selectedIndex].id
        return .selected(address: tabs[selectedIndex].address)
    }

    mutating func updateActiveAddress(_ address: String) {
        guard let index = tabs.firstIndex(where: { $0.id == activeID }) else { return }
        tabs[index].updateAddress(address)
    }

    mutating func updateActiveTitle(_ title: String?) {
        guard let index = tabs.firstIndex(where: { $0.id == activeID }) else { return }
        tabs[index].updateTitle(title)
    }

    static func isAllowedAddressMetadata(_ address: String) -> Bool {
        !address.isEmpty && address.utf8.count <= maximumAddressBytes
    }

    private mutating func allocateID() -> UInt64 {
        // At most eight entries are searched, so this is bounded O(1) in practice and avoids
        // UUID/string allocation in this hot UI path.
        while tabs.contains(where: { $0.id == nextID }) {
            nextID = nextID == .max ? 1 : nextID + 1
        }
        let allocated = nextID
        nextID = nextID == .max ? 1 : nextID + 1
        return allocated
    }
}
