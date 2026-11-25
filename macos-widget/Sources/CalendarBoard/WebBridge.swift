import AppKit
import SwiftUI
import WebKit

@MainActor
protocol WebBridgeDelegate: AnyObject {
    var currentSurfaceRawValue: String { get }
    var currentSurfaceStyleRawValue: String { get }
    func webBridgeDidUpdateModalOpen(_ open: Bool)
    func webBridgeDidUpdateInputFocus(_ focused: Bool)
    func webBridgeDidRequestSurface(_ surface: String)
    func webBridgeDidRequestCollapse()
}

@MainActor
final class WebBridge: NSObject, WKScriptMessageHandler, WKNavigationDelegate {
    let webView: WKWebView
    let timeline: IslandTimeline
    weak var delegate: WebBridgeDelegate?

    private let keepAliveWindow: NSWindow
    private let defaults = UserDefaults.standard
    private let storePrefix = "cb_store_"

    private static let inputFocusScript = #"""
    (function () {
        function isTextInput(node) {
            if (!node || node.nodeType !== 1) { return false; }
            var tag = node.tagName;
            return tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT" || node.isContentEditable;
        }
        function report(focused) {
            if (window.__cbInputFocus === focused) { return; }
            window.__cbInputFocus = focused;
            try {
                window.webkit.messageHandlers.native.postMessage({ action: "inputFocusChanged", focused: focused });
            } catch (error) {}
        }
        document.addEventListener("focusin", function (event) {
            if (isTextInput(event.target)) { report(true); }
        }, true);
        document.addEventListener("focusout", function (event) {
            if (isTextInput(event.target)) { report(false); }
        }, true);
    })();
    """#

    init(timeline: IslandTimeline) {
        UserDefaults.standard.removeObject(forKey: "cb_hover_expand")
        UserDefaults.standard.removeObject(forKey: "cb_dock_mode")
        UserDefaults.standard.removeObject(forKey: "cb_glass_style")

        self.timeline = timeline

        let configuration = WKWebViewConfiguration()
        configuration.preferences.setValue(true, forKey: "allowFileAccessFromFileURLs")
        let contentController = WKUserContentController()
        contentController.addUserScript(WKUserScript(
            source: Self.inputFocusScript,
            injectionTime: .atDocumentEnd,
            forMainFrameOnly: false
        ))
        configuration.userContentController = contentController

        let webView = WKWebView(frame: .zero, configuration: configuration)
        webView.setValue(false, forKey: "drawsBackground")
        webView.autoresizingMask = [.width, .height]
        self.webView = webView

        let keepAliveWindow = NSWindow(
            contentRect: NSRect(x: -10000, y: -10000, width: 380, height: 520),
            styleMask: [.borderless],
            backing: .buffered,
            defer: false
        )
        keepAliveWindow.isReleasedWhenClosed = false
        keepAliveWindow.alphaValue = 0
        keepAliveWindow.ignoresMouseEvents = true
        keepAliveWindow.isOpaque = false
        keepAliveWindow.backgroundColor = .clear
        keepAliveWindow.hasShadow = false
        keepAliveWindow.collectionBehavior = [.canJoinAllSpaces, .stationary, .ignoresCycle]
        keepAliveWindow.contentView = NSView(frame: NSRect(x: 0, y: 0, width: 380, height: 520))
        self.keepAliveWindow = keepAliveWindow

        super.init()

        webView.navigationDelegate = self
        contentController.add(self, name: "native")
        attachToKeepAliveWindow()
    }

    func loadUI() {
        if let bundleURL = Bundle.main.resourceURL?.appendingPathComponent("ui/widget.html"),
           FileManager.default.fileExists(atPath: bundleURL.path) {
            webView.loadFileURL(bundleURL, allowingReadAccessTo: bundleURL.deletingLastPathComponent())
            return
        }

        let workingDirectoryURL = URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
            .appendingPathComponent("ui/widget.html")
        webView.loadFileURL(
            workingDirectoryURL,
            allowingReadAccessTo: workingDirectoryURL.deletingLastPathComponent()
        )
    }

    func attachToKeepAliveWindow() {
        guard let contentView = keepAliveWindow.contentView else { return }
        if webView.superview !== contentView {
            webView.removeFromSuperview()
            contentView.addSubview(webView)
        }
        webView.frame = contentView.bounds
        keepAliveWindow.orderBack(nil)
    }

    func attach(to hostView: NSView) {
        if webView.superview !== hostView {
            webView.removeFromSuperview()
            hostView.addSubview(webView)
        }
        webView.frame = hostView.bounds
        webView.autoresizingMask = [.width, .height]
    }

    /// 让网页视图本身淡入淡出（SwiftUI 过渡可能不作用于 WKWebView）。duration 为 0 时直接设置。
    func fadeWebView(to alpha: CGFloat, duration: TimeInterval) {
        if duration <= 0 {
            webView.alphaValue = alpha
            return
        }
        NSAnimationContext.runAnimationGroup { ctx in
            ctx.duration = duration
            ctx.timingFunction = CAMediaTimingFunction(name: .easeOut)
            webView.animator().alphaValue = alpha
        }
    }

    func triggerSync() {
        evaluateJS("window.__triggerSync && window.__triggerSync();")
    }

    func openSettings() {
        evaluateJS("window.__openSettings && window.__openSettings();")
    }

    func showSurface(_ name: String, arg: Any? = nil) {
        evaluateJS(
            "window.__showSurface && window.__showSurface(\(jsString(name)), \(jsonLiteral(arg)));"
        )
    }

    func setSurfaceStyle(_ style: String) {
        evaluateJS("window.__setSurfaceStyle && window.__setSurfaceStyle(\(jsString(style)));")
    }

    func dropPayload(text: String?, fileURL: String?) {
        let payload: [String: Any] = [
            "text": text ?? NSNull(),
            "fileURL": fileURL ?? NSNull()
        ]
        guard let data = try? JSONSerialization.data(withJSONObject: payload),
              let json = String(data: data, encoding: .utf8) else {
            return
        }
        evaluateJS("window.__dropPayload && window.__dropPayload(\(json));")
    }

    func notifyWebError(_ message: String) {
        let encoded = jsString(message)
        evaluateJS(
            "window.__nativeNotice ? window.__nativeNotice(\(encoded)) : (typeof setStatus === 'function' && setStatus(\(encoded), true));"
        )
    }

    // MARK: - WKNavigationDelegate

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        var snapshot: [String: String] = [:]
        for key in ["config", "session", "cache", "meta", "demoItems", "mode"] {
            if let value = defaults.string(forKey: storePrefix + key) {
                snapshot[key] = value
            }
        }
        let initPayload: [String: Any] = [
            "store": snapshot,
            "state": [
                "surface": delegate?.currentSurfaceRawValue ?? "today",
                "surfaceStyle": delegate?.currentSurfaceStyleRawValue ?? "notch"
            ]
        ]
        if let data = try? JSONSerialization.data(withJSONObject: initPayload),
           let json = String(data: data, encoding: .utf8) {
            evaluateJS("window.__initNative && window.__initNative(\(json));")
        }
    }

    // MARK: - WKScriptMessageHandler

    func userContentController(
        _ userContentController: WKUserContentController,
        didReceive message: WKScriptMessage
    ) {
        guard let body = message.body as? [String: Any],
              let action = body["action"] as? String else { return }

        switch action {
        case "httpRequest":
            handleHttpRequest(body)
        case "saveStore":
            if let key = body["key"] as? String {
                if let value = body["value"] as? String {
                    defaults.set(value, forKey: storePrefix + key)
                } else {
                    defaults.removeObject(forKey: storePrefix + key)
                }
            }
        case "openExternal":
            guard let raw = body["url"] as? String, let url = URL(string: raw) else { return }
            openExternal(url)
        case "setModalOpen":
            if let open = body["open"] as? Bool {
                delegate?.webBridgeDidUpdateModalOpen(open)
            }
        case "inputFocusChanged":
            if let focused = body["focused"] as? Bool {
                delegate?.webBridgeDidUpdateInputFocus(focused)
            }
        case "islandTimeline":
            guard let data = try? JSONSerialization.data(withJSONObject: body),
                  let payload = try? JSONDecoder().decode(TimelinePayload.self, from: data) else {
                return
            }
            timeline.update(payload: payload)
        case "setSurface":
            if let surface = body["surface"] as? String {
                delegate?.webBridgeDidRequestSurface(surface)
            }
        case "collapse":
            delegate?.webBridgeDidRequestCollapse()
        default:
            break
        }
    }

    // MARK: - Helpers

    private func openExternal(_ url: URL) {
        guard url.isFileURL else {
            NSWorkspace.shared.open(url)
            return
        }

        guard FileManager.default.fileExists(atPath: url.path) else {
            notifyWebError("文件不存在")
            return
        }

        let isApplication = url.pathExtension.lowercased() == "app"
        let isExecutable = FileManager.default.isExecutableFile(atPath: url.path)
        if isApplication || isExecutable {
            NSWorkspace.shared.activateFileViewerSelecting([url])
        } else {
            NSWorkspace.shared.open(url)
        }
    }

    private func handleHttpRequest(_ payload: [String: Any]) {
        guard let requestId = payload["id"] as? String,
              let urlString = payload["url"] as? String,
              let url = URL(string: urlString) else { return }

        var request = URLRequest(url: url)
        request.httpMethod = (payload["method"] as? String) ?? "GET"
        request.timeoutInterval = 12

        if let headers = payload["headers"] as? [String: String] {
            for (key, value) in headers {
                request.setValue(value, forHTTPHeaderField: key)
            }
        }
        if let bodyString = payload["body"] as? String {
            request.httpBody = bodyString.data(using: .utf8)
        }

        let task = URLSession.shared.dataTask(with: request) { [weak self] data, response, error in
            var result: [String: Any] = ["id": requestId]
            if let error = error {
                result["networkError"] = error.localizedDescription
            } else {
                result["status"] = (response as? HTTPURLResponse)?.statusCode ?? 0
                result["text"] = data.flatMap { String(data: $0, encoding: .utf8) } ?? ""
            }
            DispatchQueue.main.async {
                guard let self = self,
                      let jsonData = try? JSONSerialization.data(withJSONObject: result),
                      let json = String(data: jsonData, encoding: .utf8) else { return }
                self.evaluateJS("window.__nativeHttpCallback && window.__nativeHttpCallback(\(json));")
            }
        }
        task.resume()
    }

    private func evaluateJS(_ script: String) {
        webView.evaluateJavaScript(script, completionHandler: nil)
    }

    private func jsString(_ value: String) -> String {
        guard let data = try? JSONEncoder().encode(value),
              let encoded = String(data: data, encoding: .utf8) else {
            return "\"\""
        }
        return encoded
    }

    private func jsonLiteral(_ value: Any?) -> String {
        guard let value = value else { return "null" }
        if let data = try? JSONSerialization.data(withJSONObject: value, options: [.fragmentsAllowed]),
           let json = String(data: data, encoding: .utf8) {
            return json
        }
        return "null"
    }
}

final class WebViewHostView: NSView {
    weak var bridge: WebBridge?

    override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        if window != nil {
            bridge?.attach(to: self)
        }
    }

    override func layout() {
        super.layout()
        if window != nil {
            bridge?.attach(to: self)
        }
    }
}

struct WebViewHostRepresentable: NSViewRepresentable {
    let bridge: WebBridge

    func makeNSView(context: Context) -> WebViewHostView {
        let view = WebViewHostView()
        view.bridge = bridge
        return view
    }

    func updateNSView(_ nsView: WebViewHostView, context: Context) {
        nsView.bridge = bridge
        if nsView.window != nil {
            bridge.attach(to: nsView)
        }
    }
}
