import UIKit

/// Shared accessibility behavior for the native application chrome.
///
/// UIKit already adapts its standard controls to VoiceOver, Voice Control,
/// Switch Control, Bold Text, Button Shapes, contrast, and appearance
/// settings. These helpers cover the places where Shakescape deliberately
/// uses custom typography or motion.
@MainActor
enum AppAccessibility {
    static func scaledSystemFont(
        size: CGFloat,
        weight: UIFont.Weight,
        textStyle: UIFont.TextStyle
    ) -> UIFont {
        UIFontMetrics(forTextStyle: textStyle).scaledFont(
            for: .systemFont(ofSize: size, weight: weight)
        )
    }

    static func scaledMonospacedFont(
        size: CGFloat,
        weight: UIFont.Weight,
        textStyle: UIFont.TextStyle
    ) -> UIFont {
        UIFontMetrics(forTextStyle: textStyle).scaledFont(
            for: .monospacedSystemFont(ofSize: size, weight: weight)
        )
    }

    static func scaledNamedFont(
        name: String,
        size: CGFloat,
        fallbackWeight: UIFont.Weight,
        textStyle: UIFont.TextStyle
    ) -> UIFont {
        let base = UIFont(name: name, size: size)
            ?? .systemFont(ofSize: size, weight: fallbackWeight)
        return UIFontMetrics(forTextStyle: textStyle).scaledFont(for: base)
    }

    static func animate(
        duration: TimeInterval,
        options: UIView.AnimationOptions = [],
        animations: @escaping () -> Void,
        completion: ((Bool) -> Void)? = nil
    ) {
        guard !UIAccessibility.isReduceMotionEnabled else {
            UIView.performWithoutAnimation(animations)
            completion?(true)
            return
        }
        UIView.animate(
            withDuration: duration,
            delay: 0,
            options: options,
            animations: animations,
            completion: completion
        )
    }

    static func configureModal(_ view: UIView) {
        view.accessibilityViewIsModal = true
    }
}
