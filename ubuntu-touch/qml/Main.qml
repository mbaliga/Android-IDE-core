// SPDX-License-Identifier: Apache-2.0
// Entire native layer: a window hosting a WebView on the bundled, read-only www/ directory.
// It links nothing: the OS's own Lomiri QML runtime loads this text file. No networking,
// no URL loading beyond file:// in this click, no navigation away from the bundle.
import QtQuick 2.12
import Lomiri.Components 1.3
import Morph.Web 0.1

MainView {
    id: root
    applicationName: "fonebrew-diff.asoc"
    automaticOrientation: true
    width: units.gu(45)
    height: units.gu(80)

    WebView {
        id: web
        anchors.fill: parent
        url: Qt.resolvedUrl("../www/index.html")
        // The page needs only itself. Refuse every navigation that is not into the bundle.
        onNavigationRequested: {
            var u = request.url.toString()
            if (u.indexOf("file://") !== 0 || u.indexOf("/www/") < 0) {
                request.action = WebEngineNavigationRequest.IgnoreRequest
            }
        }
    }
}
