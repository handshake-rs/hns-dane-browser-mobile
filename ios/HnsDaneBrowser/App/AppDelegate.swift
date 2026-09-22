import UIKit
import UserNotifications

@main
final class AppDelegate: UIResponder, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    let browserProcess = BrowserProcess()
    private var pendingAtomicSwapNotificationOpen = false

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
        // Swap transitions are useful precisely while the wallet is doing
        // other foreground work, so match Android's visible notification.
        return [.banner, .list, .sound]
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse
    ) async {
        guard response.notification.request.content.categoryIdentifier ==
                "ATOMIC_SWAP_STATUS" else { return }
        pendingAtomicSwapNotificationOpen = true
        routePendingAtomicSwapNotification()
    }

    func routePendingAtomicSwapNotification() {
        guard pendingAtomicSwapNotificationOpen else { return }
        for scene in UIApplication.shared.connectedScenes {
            guard let delegate = scene.delegate as? SceneDelegate,
                  delegate.presentWalletFromAtomicSwapNotification() else { continue }
            pendingAtomicSwapNotificationOpen = false
            return
        }
    }
}
