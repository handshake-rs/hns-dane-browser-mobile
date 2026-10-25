import UIKit
import UserNotifications

@main
final class AppDelegate: UIResponder, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    let browserProcess = BrowserProcess()
    private var pendingAtomicSwapNotificationSessionID: String?

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        return true
    }

    func application(
        _ application: UIApplication,
        configurationForConnecting connectingSceneSession: UISceneSession,
        options: UIScene.ConnectionOptions
    ) -> UISceneConfiguration {
        let configuration = UISceneConfiguration(
            name: "Browser",
            sessionRole: connectingSceneSession.role
        )
        configuration.delegateClass = SceneDelegate.self
        return configuration
    }

    func applicationWillTerminate(_ application: UIApplication) {
        browserProcess.close()
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        guard notification.request.content.categoryIdentifier == "ATOMIC_SWAP_STATUS" else {
            return []
        }
        // Routine progress stays available in the list; verified actions and
        // outcomes interrupt other foreground work.
        if notification.request.content.interruptionLevel == .passive {
            return [.list]
        }
        return [.banner, .list, .sound]
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse
    ) async {
        guard response.notification.request.content.categoryIdentifier ==
                "ATOMIC_SWAP_STATUS" else { return }
        let identifier = response.notification.request.identifier
        let sessionID: String
        if identifier.hasPrefix("atomic-swap:") {
            sessionID = String(identifier.dropFirst("atomic-swap:".count))
        } else if identifier.hasPrefix("atomic-swap-funding-warning:") {
            sessionID = String(identifier.dropFirst("atomic-swap-funding-warning:".count))
        } else {
            return
        }
        pendingAtomicSwapNotificationSessionID = sessionID
        routePendingAtomicSwapNotification()
    }

    func routePendingAtomicSwapNotification() {
        guard let sessionID = pendingAtomicSwapNotificationSessionID else { return }
        for scene in UIApplication.shared.connectedScenes {
            guard let delegate = scene.delegate as? SceneDelegate else { continue }
            delegate.prepareAtomicSwapNotification(sessionID: sessionID)
            guard delegate.presentWalletFromAtomicSwapNotification() else { continue }
            pendingAtomicSwapNotificationSessionID = nil
            return
        }
    }
}
