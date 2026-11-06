import SwiftUI
import RecordLabsShared

/// The iPhone app: the shared Record Labs app (Compose, from Kotlin) full screen.
@main
struct RecordLabsApp: App {
    var body: some Scene {
        WindowGroup {
            SharedAppView()
                .ignoresSafeArea()
                .preferredColorScheme(.dark)
        }
    }
}

struct SharedAppView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
