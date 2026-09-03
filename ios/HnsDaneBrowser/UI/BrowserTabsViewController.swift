import UIKit

@MainActor
final class BrowserTabsViewController: UITableViewController {
    var onNewTab: (() -> BrowserTabs.Snapshot?)?
    var onSelectTab: ((UInt64) -> Void)?
    var onCloseTab: ((UInt64) -> BrowserTabs.Snapshot?)?

    private var snapshot: BrowserTabs.Snapshot

    init(snapshot: BrowserTabs.Snapshot) {
        self.snapshot = snapshot
        super.init(style: .insetGrouped)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is unavailable")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "Tabs"
        tableView.accessibilityIdentifier = "browser.tabs.list"
        tableView.rowHeight = UITableView.automaticDimension
        tableView.estimatedRowHeight = 64
        navigationItem.leftBarButtonItem = UIBarButtonItem(
            systemItem: .add,
            primaryAction: UIAction { [weak self] _ in self?.newTab() }
        )
        navigationItem.rightBarButtonItem = UIBarButtonItem(
            systemItem: .done,
            primaryAction: UIAction { [weak self] _ in self?.dismiss(animated: true) }
        )
        updateNewTabAvailability()
    }

    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        snapshot.tabs.count
    }

    override func tableView(
        _ tableView: UITableView,
        cellForRowAt indexPath: IndexPath
    ) -> UITableViewCell {
        let reuseIdentifier = "browser-tab"
        let cell = tableView.dequeueReusableCell(withIdentifier: reuseIdentifier)
            ?? UITableViewCell(style: .subtitle, reuseIdentifier: reuseIdentifier)
        let tab = snapshot.tabs[indexPath.row]
        cell.textLabel?.text = tab.displayTitle
        cell.textLabel?.numberOfLines = 1
        cell.detailTextLabel?.text = tab.displayAddress
        cell.detailTextLabel?.numberOfLines = 1
        cell.detailTextLabel?.textColor = .secondaryLabel
        cell.accessoryType = tab.id == snapshot.activeID ? .checkmark : .none
        cell.accessibilityIdentifier = "browser.tab.\(tab.id)"
        cell.accessibilityLabel = "\(tab.displayTitle), \(tab.displayAddress)"
        cell.accessibilityValue = tab.id == snapshot.activeID ? "Current tab" : nil
        return cell
    }

    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        let id = snapshot.tabs[indexPath.row].id
        let selection = onSelectTab
        dismiss(animated: true) { selection?(id) }
    }

    override func tableView(
        _ tableView: UITableView,
        trailingSwipeActionsConfigurationForRowAt indexPath: IndexPath
    ) -> UISwipeActionsConfiguration? {
        guard snapshot.tabs.count > 1 else { return nil }
        let id = snapshot.tabs[indexPath.row].id
        let close = UIContextualAction(style: .destructive, title: "Close") {
            [weak self] _, _, completion in
            guard let self, let updated = self.onCloseTab?(id) else {
                completion(false)
                return
            }
            self.apply(updated)
            completion(true)
        }
        close.image = UIImage(systemName: "xmark")
        return UISwipeActionsConfiguration(actions: [close])
    }

    private func newTab() {
        guard let updated = onNewTab?() else { return }
        apply(updated)
        dismiss(animated: true)
    }

    private func apply(_ snapshot: BrowserTabs.Snapshot) {
        self.snapshot = snapshot
        tableView.reloadData()
        updateNewTabAvailability()
    }

    private func updateNewTabAvailability() {
        navigationItem.leftBarButtonItem?.isEnabled = snapshot.tabs.count < BrowserTabs.maximumCount
    }
}
