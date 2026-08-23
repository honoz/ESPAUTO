/*
ESPAUTO
Copyright (c) 2026 honoz
Licensed under the MIT License.
*/

import SwiftUI

@main
struct ESPAUTOApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
                .frame(minWidth: 900, minHeight: 750)
        }
        .windowStyle(.hiddenTitleBar)
        .windowResizability(.contentSize)
        .defaultSize(width: 1000, height: 780)
    }
}
