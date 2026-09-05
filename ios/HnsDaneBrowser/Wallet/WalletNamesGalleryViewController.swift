import UIKit

@MainActor
final class WalletNamesGalleryViewController: UIViewController,
    UISearchTextFieldDelegate {

    var onOptions: (() -> Void)?

    private var names: [NativeHnsReadSnapshot.KnownName]
    private var totalNameCount: Int
    private var snapshotHeight: UInt64
    private var actionsAvailable: Bool
    private var selectedIndex = 0

    private let searchContainer = UIView()
    private let searchField = UISearchTextField()
    private let card = HolographicWalletNameCardView()
    private let positionLabel = UILabel()
    private let previousButton = UIButton(type: .system)
    private let searchButton = UIButton(type: .system)
    private let optionsButton = UIButton(type: .system)
    private let nextButton = UIButton(type: .system)
    private var searchHeightConstraint: NSLayoutConstraint!
    private var searchIsVisible = false

    init(
        names: [NativeHnsReadSnapshot.KnownName],
        totalNameCount: Int,
        snapshotHeight: UInt64 = 0,
        actionsAvailable: Bool
    ) {
        self.names = names
        self.totalNameCount = max(totalNameCount, names.count)
        self.snapshotHeight = snapshotHeight
        self.actionsAvailable = actionsAvailable
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is unavailable")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "Names"
        navigationItem.largeTitleDisplayMode = .never
        view.backgroundColor = UIColor(red: 0.025, green: 0.035, blue: 0.065, alpha: 1)
        configureSearch()
        configureCard()
        configureFooter()
        renderSelection()
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        searchField.resignFirstResponder()
    }

    func update(
        names: [NativeHnsReadSnapshot.KnownName],
        totalNameCount: Int,
        snapshotHeight: UInt64,
        actionsAvailable: Bool
    ) {
        let selectedName = self.names.indices.contains(selectedIndex)
            ? self.names[selectedIndex].name
            : nil
        self.names = names
        self.totalNameCount = max(totalNameCount, names.count)
        self.snapshotHeight = snapshotHeight
        self.actionsAvailable = actionsAvailable
        if let selectedName,
           let replacement = names.firstIndex(where: { $0.name == selectedName }) {
            selectedIndex = replacement
        } else {
            selectedIndex = min(selectedIndex, max(names.count - 1, 0))
        }
        guard isViewLoaded else { return }
        updateSearchSuggestions()
        renderSelection()
    }

    private func configureSearch() {
        searchContainer.translatesAutoresizingMaskIntoConstraints = false
        searchContainer.clipsToBounds = true
        searchContainer.alpha = 0
        view.addSubview(searchContainer)

        searchField.translatesAutoresizingMaskIntoConstraints = false
        searchField.placeholder = "Search tracked names…"
        searchField.autocapitalizationType = .none
        searchField.autocorrectionType = .no
        searchField.spellCheckingType = .no
        searchField.smartDashesType = .no
        searchField.smartQuotesType = .no
        searchField.keyboardType = .asciiCapable
        searchField.returnKeyType = .search
        searchField.delegate = self
        searchField.accessibilityIdentifier = "wallet.names.search"
        searchField.addTarget(self, action: #selector(searchTextChanged), for: .editingChanged)
        searchContainer.addSubview(searchField)

        searchHeightConstraint = searchContainer.heightAnchor.constraint(equalToConstant: 0)
        NSLayoutConstraint.activate([
            searchContainer.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            searchContainer.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 16),
            searchContainer.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -16),
            searchHeightConstraint,
            searchField.leadingAnchor.constraint(equalTo: searchContainer.leadingAnchor),
            searchField.trailingAnchor.constraint(equalTo: searchContainer.trailingAnchor),
            searchField.topAnchor.constraint(equalTo: searchContainer.topAnchor, constant: 4),
            searchField.heightAnchor.constraint(equalToConstant: 48),
        ])
    }

    private func configureCard() {
        card.translatesAutoresizingMaskIntoConstraints = false
        card.accessibilityIdentifier = "wallet.names.holocard"
        view.addSubview(card)
        NSLayoutConstraint.activate([
            card.topAnchor.constraint(equalTo: searchContainer.bottomAnchor, constant: 8),
            card.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 16),
            card.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -16),
        ])
    }

    private func configureFooter() {
        let footer = UIStackView()
        footer.translatesAutoresizingMaskIntoConstraints = false
        footer.axis = .vertical
        footer.spacing = 7
        view.addSubview(footer)

        positionLabel.font = .monospacedSystemFont(ofSize: 12, weight: .bold)
        positionLabel.textColor = UIColor(red: 0.62, green: 0.70, blue: 0.80, alpha: 1)
        positionLabel.textAlignment = .center
        positionLabel.accessibilityIdentifier = "wallet.names.position"
        footer.addArrangedSubview(positionLabel)

        let buttons = UIStackView(arrangedSubviews: [
            previousButton, searchButton, optionsButton, nextButton,
        ])
        buttons.axis = .horizontal
        buttons.spacing = 6
        buttons.distribution = .fillEqually
        footer.addArrangedSubview(buttons)

        configureFooterButton(previousButton, title: "← PREVIOUS", action: #selector(showPrevious))
        configureFooterButton(searchButton, title: "SEARCH", action: #selector(toggleSearch))
        configureFooterButton(optionsButton, title: "OPTIONS", action: #selector(showOptions), primary: true)
        configureFooterButton(nextButton, title: "NEXT →", action: #selector(showNext))

        NSLayoutConstraint.activate([
            footer.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 16),
            footer.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -16),
            footer.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -8),
            card.bottomAnchor.constraint(equalTo: footer.topAnchor, constant: -8),
            buttons.heightAnchor.constraint(equalToConstant: 46),
        ])
    }

    private func configureFooterButton(
        _ button: UIButton,
        title: String,
        action: Selector,
        primary: Bool = false
    ) {
        var configuration = UIButton.Configuration.tinted()
        configuration.title = title
        configuration.cornerStyle = .large
        configuration.baseForegroundColor = primary ? .systemTeal : .systemIndigo
        configuration.baseBackgroundColor = UIColor.clear
        configuration.contentInsets = NSDirectionalEdgeInsets(top: 5, leading: 2, bottom: 5, trailing: 2)
        button.configuration = configuration
        button.titleLabel?.font = .systemFont(ofSize: 10, weight: .bold)
        button.layer.borderWidth = 1
        button.layer.borderColor = (primary ? UIColor.systemTeal : UIColor.systemIndigo).cgColor
        button.layer.cornerRadius = 14
        button.addTarget(self, action: action, for: .touchUpInside)
    }

    private func renderSelection() {
        if names.isEmpty {
            positionLabel.text = "NO TRACKED NAMES"
            card.configure(name: nil, snapshotHeight: snapshotHeight)
        } else {
            selectedIndex = min(max(selectedIndex, 0), names.count - 1)
            positionLabel.text = "NAME \(selectedIndex + 1) OF \(totalNameCount)"
            card.configure(name: names[selectedIndex], snapshotHeight: snapshotHeight)
        }
        previousButton.isEnabled = actionsAvailable && selectedIndex > 0
        nextButton.isEnabled = actionsAvailable && selectedIndex + 1 < names.count
        searchButton.isEnabled = actionsAvailable && !names.isEmpty
        optionsButton.isEnabled = actionsAvailable
    }

    @objc private func showPrevious() {
        guard selectedIndex > 0 else { return }
        selectedIndex -= 1
        closeSearch(animated: false)
        renderSelection()
    }

    @objc private func showNext() {
        guard selectedIndex + 1 < names.count else { return }
        selectedIndex += 1
        closeSearch(animated: false)
        renderSelection()
    }

    @objc private func showOptions() {
        closeSearch(animated: true)
        onOptions?()
    }

    @objc private func toggleSearch() {
        searchIsVisible ? closeSearch(animated: true) : openSearch()
    }

    private func openSearch() {
        guard !searchIsVisible else { return }
        searchIsVisible = true
        searchHeightConstraint.constant = 56
        searchContainer.transform = CGAffineTransform(translationX: 0, y: -56)
        view.layoutIfNeeded()
        UIView.animate(withDuration: 0.22, delay: 0, options: [.curveEaseOut]) {
            self.searchContainer.alpha = 1
            self.searchContainer.transform = .identity
            self.view.layoutIfNeeded()
        } completion: { _ in
            self.searchField.becomeFirstResponder()
            self.updateSearchSuggestions()
        }
    }

    private func closeSearch(animated: Bool) {
        guard searchIsVisible else {
            searchField.resignFirstResponder()
            return
        }
        searchIsVisible = false
        searchField.resignFirstResponder()
        searchField.text = nil
        searchField.searchSuggestions = nil
        searchHeightConstraint.constant = 0
        let changes = {
            self.searchContainer.alpha = 0
            self.searchContainer.transform = CGAffineTransform(translationX: 0, y: -56)
            self.view.layoutIfNeeded()
        }
        if animated {
            UIView.animate(withDuration: 0.22, delay: 0, options: [.curveEaseIn], animations: changes)
        } else {
            changes()
        }
    }

    @objc private func searchTextChanged() {
        updateSearchSuggestions()
    }

    private func updateSearchSuggestions() {
        let query = canonicalSearchText(searchField.text ?? "") ?? ""
        let candidates = names.lazy.map(\.name).filter {
            query.isEmpty || $0.localizedCaseInsensitiveContains(query)
        }.prefix(12)
        searchField.searchSuggestions = candidates.map {
            UISearchSuggestionItem(localizedSuggestion: displayHandshakeNameText($0))
        }
    }

    func searchTextField(
        _ searchTextField: UISearchTextField,
        didSelect suggestion: UISearchSuggestion
    ) {
        guard let name = suggestion.localizedSuggestion else { return }
        selectName(named: name)
    }

    func textFieldShouldReturn(_ textField: UITextField) -> Bool {
        guard let query = canonicalSearchText(textField.text ?? "") else { return false }
        selectName(named: query)
        return true
    }

    private func selectName(named name: String) {
        let canonical = canonicalSearchText(name) ?? name
        guard let index = names.firstIndex(where: { $0.name == canonical }) else {
            searchField.text = name
            UINotificationFeedbackGenerator().notificationOccurred(.error)
            return
        }
        selectedIndex = index
        closeSearch(animated: true)
        renderSelection()
    }

    private func canonicalSearchText(_ raw: String) -> String? {
        let value = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
            .replacingOccurrences(of: "\\.$", with: "", options: .regularExpression)
        return canonicalHandshakeNameImportText(value)
    }
}

@MainActor
private final class HolographicWalletNameCardView: UIView {
    private let backgroundGradient = CAGradientLayer()
    private let lightGradient = CAGradientLayer()
    private let borderLayer = CAShapeLayer()
    private let content = UIStackView()
    private let titleLabel = UILabel()
    private let cardInset: CGFloat = 10

    override init(frame: CGRect) {
        super.init(frame: frame)
        configureView()
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is unavailable")
    }

    private func configureView() {
        layer.shadowColor = UIColor.black.cgColor
        layer.shadowOpacity = 0.48
        layer.shadowRadius = 12
        layer.shadowOffset = CGSize(width: 0, height: 8)

        backgroundGradient.colors = [
            UIColor(red: 0.05, green: 0.79, blue: 0.88, alpha: 1).cgColor,
            UIColor(red: 0.78, green: 0.08, blue: 0.68, alpha: 1).cgColor,
            UIColor(red: 0.36, green: 0.24, blue: 0.98, alpha: 1).cgColor,
            UIColor(red: 1.00, green: 0.42, blue: 0.50, alpha: 1).cgColor,
        ]
        backgroundGradient.locations = [0, 0.34, 0.68, 1]
        backgroundGradient.startPoint = CGPoint(x: 0, y: 0)
        backgroundGradient.endPoint = CGPoint(x: 1, y: 1)
        backgroundGradient.cornerRadius = 24
        layer.insertSublayer(backgroundGradient, at: 0)

        lightGradient.colors = [
            UIColor.white.withAlphaComponent(0.42).cgColor,
            UIColor.clear.cgColor,
            UIColor.systemTeal.withAlphaComponent(0.24).cgColor,
        ]
        lightGradient.locations = [0, 0.48, 1]
        lightGradient.startPoint = CGPoint(x: 0, y: 0.1)
        lightGradient.endPoint = CGPoint(x: 1, y: 0.9)
        lightGradient.cornerRadius = 24
        layer.insertSublayer(lightGradient, above: backgroundGradient)

        borderLayer.fillColor = UIColor.clear.cgColor
        borderLayer.strokeColor = UIColor.systemTeal.cgColor
        borderLayer.lineWidth = 2.5
        layer.addSublayer(borderLayer)

        content.translatesAutoresizingMaskIntoConstraints = false
        content.axis = .vertical
        content.spacing = 3
        content.distribution = .fillProportionally
        addSubview(content)
        NSLayoutConstraint.activate([
            content.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 18),
            content.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -18),
            content.topAnchor.constraint(equalTo: topAnchor, constant: 15),
            content.bottomAnchor.constraint(lessThanOrEqualTo: bottomAnchor, constant: -15),
        ])

        let pan = UIPanGestureRecognizer(target: self, action: #selector(tiltCard(_:)))
        addGestureRecognizer(pan)
        isUserInteractionEnabled = true
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        backgroundGradient.frame = bounds.insetBy(dx: cardInset, dy: cardInset)
        lightGradient.frame = backgroundGradient.frame
        borderLayer.path = UIBezierPath(
            roundedRect: bounds.insetBy(dx: cardInset, dy: cardInset),
            cornerRadius: 24
        ).cgPath
        layer.shadowPath = borderLayer.path
    }

    func configure(name: NativeHnsReadSnapshot.KnownName?, snapshotHeight: UInt64) {
        content.arrangedSubviews.forEach {
            content.removeArrangedSubview($0)
            $0.removeFromSuperview()
        }
        guard let name else {
            titleLabel.attributedText = tronTitle("NO NAME")
            content.addArrangedSubview(titleLabel)
            accessibilityLabel = "No tracked Handshake name"
            return
        }

        let displayedName = displayHandshakeNameText(name.name)
        titleLabel.attributedText = tronTitle(displayedName)
        titleLabel.textAlignment = .center
        titleLabel.adjustsFontSizeToFitWidth = true
        titleLabel.minimumScaleFactor = 0.35
        titleLabel.numberOfLines = 1
        titleLabel.setContentHuggingPriority(.required, for: .vertical)
        content.addArrangedSubview(titleLabel)

        addSection("CHAIN IDENTITY", rows: [
            [("OWNERSHIP", ownershipLabel(name.ownershipStatus)),
             ("RESOURCE STATUS", codeLabel(name.resourceStatus))],
            [("PROOF HEIGHT", String(name.proofHeight)),
             ("SNAPSHOT HEIGHT", String(snapshotHeight == 0 ? name.proofHeight : snapshotHeight))],
            [("REGISTERED", optionalBoolean(name.registered)),
             ("EXPIRED BEFORE", optionalBoolean(name.expired))],
            [("NAME HASH", name.nameHash)],
        ], singleLineRows: [3])

        if let state = name.canonicalState {
            addSection("COVENANT STATE", rows: [
                [("VALUE", "\(formatHns(state.valueBaseUnits)) HNS"),
                 ("HIGHEST BID", "\(formatHns(state.highestBaseUnits)) HNS")],
                [("START HEIGHT", String(state.startHeight)),
                 ("RENEWAL HEIGHT", String(state.renewalHeight))],
                [("TRANSFER HEIGHT", String(state.transferHeight)),
                 ("REVOKED HEIGHT", String(state.revokedHeight))],
                [("CLAIMED HEIGHT", String(state.claimedHeight)),
                 ("RENEWAL COVENANTS", String(state.renewals))],
                [("WEAK NAME", state.weak ? "YES" : "NO")],
            ])
        } else {
            addSection("COVENANT STATE", rows: [[("CANONICAL STATE", "UNAVAILABLE")]])
        }

        let resource = name.rawResourceHex.map(compactResourceHex) ?? "UNAVAILABLE"
        addSection("RESOURCE DATA", rows: [
            [("RECORDS", name.resourceRecordCount.map { String($0) } ?? "UNKNOWN"),
             ("BYTES", name.rawResourceHex.map { String($0.utf8.count / 2) } ?? "UNKNOWN")],
            [("RAW RESOURCE HEX PREVIEW", resource.isEmpty ? "EMPTY" : resource)],
        ], singleLineRows: [1])
        accessibilityLabel = "Tracked Handshake name \(displayedName). \(ownershipLabel(name.ownershipStatus)), \(codeLabel(name.resourceStatus))"
    }

    private func addSection(
        _ title: String,
        rows: [[(String, String)]],
        singleLineRows: Set<Int> = []
    ) {
        let heading = UILabel()
        heading.text = title
        heading.font = UIFont(name: "AvenirNext-Heavy", size: 11) ?? .systemFont(ofSize: 11, weight: .heavy)
        heading.textColor = UIColor(red: 1, green: 0.88, blue: 0.24, alpha: 1)
        heading.layer.shadowColor = UIColor.black.cgColor
        heading.layer.shadowOpacity = 1
        heading.layer.shadowRadius = 1
        content.addArrangedSubview(heading)

        rows.enumerated().forEach { index, stats in
            let row = UIStackView()
            row.axis = .horizontal
            row.spacing = 5
            row.distribution = .fillEqually
            stats.forEach { label, value in
                row.addArrangedSubview(statView(
                    label: label,
                    value: value,
                    singleLine: singleLineRows.contains(index),
                    monospace: label.contains("HASH") || label.contains("HEX")
                ))
            }
            content.addArrangedSubview(row)
        }
    }

    private func statView(
        label: String,
        value: String,
        singleLine: Bool,
        monospace: Bool
    ) -> UIView {
        let cell = UIStackView()
        cell.axis = .vertical
        cell.spacing = 0
        cell.isLayoutMarginsRelativeArrangement = true
        cell.directionalLayoutMargins = NSDirectionalEdgeInsets(top: 3, leading: 7, bottom: 3, trailing: 7)
        cell.backgroundColor = UIColor(red: 0.02, green: 0.04, blue: 0.10, alpha: 0.64)
        cell.layer.cornerRadius = 9

        let heading = UILabel()
        heading.text = label
        heading.font = .systemFont(ofSize: 8.5, weight: .heavy)
        heading.textColor = .systemTeal
        heading.adjustsFontSizeToFitWidth = true
        heading.minimumScaleFactor = 0.7
        heading.numberOfLines = 1
        cell.addArrangedSubview(heading)

        let detail = UILabel()
        detail.text = value
        detail.font = monospace
            ? .monospacedSystemFont(ofSize: 11, weight: .medium)
            : .systemFont(ofSize: 12.5, weight: .bold)
        detail.textColor = .white
        detail.layer.shadowColor = UIColor.black.cgColor
        detail.layer.shadowOpacity = 1
        detail.layer.shadowRadius = 1.2
        detail.numberOfLines = singleLine ? 1 : 2
        detail.adjustsFontSizeToFitWidth = true
        detail.minimumScaleFactor = singleLine ? 0.30 : 0.70
        detail.lineBreakMode = singleLine ? .byClipping : .byWordWrapping
        cell.addArrangedSubview(detail)
        return cell
    }

    private func tronTitle(_ text: String) -> NSAttributedString {
        let requiresSystemGlyphs = text.unicodeScalars.contains { $0.value >= 0x80 }
        let font = requiresSystemGlyphs
            ? UIFont.systemFont(ofSize: 40, weight: .black)
            : (UIFont(name: "AvenirNext-Heavy", size: 40)
                ?? .systemFont(ofSize: 40, weight: .black))
        return NSAttributedString(string: text, attributes: [
            .font: font,
            .foregroundColor: UIColor(red: 0.10, green: 1, blue: 0.91, alpha: 1),
            .strokeColor: UIColor.black,
            .strokeWidth: -4.0,
            .kern: 0.5,
        ])
    }

    private func ownershipLabel(_ status: String) -> String {
        switch status {
        case "watchOnlyCanonicalStateDecoderUnavailable", "watchOnlyOwnerTransactionUnavailable":
            return "WATCH ONLY"
        case "walletContextUnavailable":
            return "UNCLASSIFIED"
        default:
            return codeLabel(status).uppercased()
        }
    }

    private func codeLabel(_ value: String) -> String {
        WalletReadPresenter.codeLabel(value).uppercased()
    }

    private func optionalBoolean(_ value: Bool?) -> String {
        value.map { $0 ? "YES" : "NO" } ?? "UNKNOWN"
    }

    private func formatHns(_ baseUnits: String) -> String {
        WalletReadPresenter.formatHnsBaseUnits(baseUnits)
    }

    private func compactResourceHex(_ raw: String) -> String {
        guard raw.count > 72 else { return raw }
        return String(raw.prefix(53)) + "…" + String(raw.suffix(18))
    }

    @objc private func tiltCard(_ gesture: UIPanGestureRecognizer) {
        let point = gesture.location(in: self)
        switch gesture.state {
        case .began, .changed:
            let normalizedX = min(max(point.x / max(bounds.width, 1), 0), 1) - 0.5
            let normalizedY = min(max(point.y / max(bounds.height, 1), 0), 1) - 0.5
            var transform = CATransform3DIdentity
            transform.m34 = -1 / 850
            transform = CATransform3DRotate(transform, normalizedY * -0.24, 1, 0, 0)
            transform = CATransform3DRotate(transform, normalizedX * 0.24, 0, 1, 0)
            layer.transform = transform
            lightGradient.startPoint = CGPoint(x: 0.5 + normalizedX, y: 0.5 + normalizedY)
            lightGradient.endPoint = CGPoint(x: 0.5 - normalizedX, y: 0.5 - normalizedY)
        default:
            UIView.animate(withDuration: 0.28, delay: 0, options: [.curveEaseOut]) {
                self.layer.transform = CATransform3DIdentity
                self.lightGradient.startPoint = CGPoint(x: 0, y: 0.1)
                self.lightGradient.endPoint = CGPoint(x: 1, y: 0.9)
            }
        }
    }
}
