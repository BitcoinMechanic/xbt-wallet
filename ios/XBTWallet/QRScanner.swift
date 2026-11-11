import SwiftUI
import AVFoundation

struct QRScanner: View {
    let receive: (ScanResult) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var allowed = false
    @State private var message = "Requesting camera permission…"
    var body: some View {
        NavigationStack {
            ZStack {
                Palette.background.ignoresSafeArea()
                if allowed {
                    CameraView { text in
                        do { receive(try ScanResult.parse(text)) }
                        catch { message = error.localizedDescription; allowed = false }
                    }.ignoresSafeArea(edges: .bottom)
                } else {
                    VStack(spacing: 20) {
                        Image(systemName: "qrcode.viewfinder").font(.largeTitle).foregroundStyle(Palette.rope)
                        Text(message).multilineTextAlignment(.center)
                        Button("Open Settings") { if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) } }
                    }.padding()
                }
            }.navigationTitle("Scan QR")
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } } }
                .task {
                    guard AVCaptureDevice.default(for: .video) != nil else {
                        message = "No camera is available on this device. You can paste an invoice or address instead."; return
                    }
                    let status = AVCaptureDevice.authorizationStatus(for: .video)
                    if status == .authorized { allowed = true }
                    else if status == .notDetermined { allowed = await AVCaptureDevice.requestAccess(for: .video) }
                    if !allowed { message = "Camera access is disabled. Enable it in Settings, or close this screen and paste your payment details." }
                }
        }
    }
}
struct CameraView: UIViewControllerRepresentable {
    let receive: (String) -> Void
    func makeUIViewController(context: Context) -> ScannerController { ScannerController(receive: receive) }
    func updateUIViewController(_ controller: ScannerController, context: Context) {}
    static func dismantleUIViewController(_ controller: ScannerController, coordinator: ()) { controller.stop() }
}
final class ScannerController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    private let session = AVCaptureSession()
    private let queue = DispatchQueue(label: "org.xbt.wallet.camera")
    private let receive: (String) -> Void
    private var preview: AVCaptureVideoPreviewLayer?
    private var delivered = false
    init(receive: @escaping (String) -> Void) { self.receive = receive; super.init(nibName: nil, bundle: nil) }
    required init?(coder: NSCoder) { fatalError("Use init(receive:)") }
    override func viewDidLoad() {
        super.viewDidLoad()
        let layer = AVCaptureVideoPreviewLayer(session: session)
        layer.videoGravity = .resizeAspectFill
        view.layer.addSublayer(layer); preview = layer
        queue.async { [weak self] in
            guard let self, let device = AVCaptureDevice.default(for: .video),
                  let input = try? AVCaptureDeviceInput(device: device) else { return }
            session.beginConfiguration()
            guard session.canAddInput(input) else { session.commitConfiguration(); return }
            session.addInput(input)
            let output = AVCaptureMetadataOutput()
            guard session.canAddOutput(output) else { session.commitConfiguration(); return }
            session.addOutput(output)
            output.setMetadataObjectsDelegate(self, queue: .main)
            if output.availableMetadataObjectTypes.contains(.qr) { output.metadataObjectTypes = [.qr] }
            session.commitConfiguration()
            session.startRunning()
        }
    }
    override func viewDidLayoutSubviews() { super.viewDidLayoutSubviews(); preview?.frame = view.bounds }
    func stop() { queue.async { [weak self] in self?.session.stopRunning() } }
    func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject], from connection: AVCaptureConnection) {
        guard !delivered, let text = (metadataObjects.first as? AVMetadataMachineReadableCodeObject)?.stringValue else { return }
        delivered = true; stop(); receive(text)
    }
}
