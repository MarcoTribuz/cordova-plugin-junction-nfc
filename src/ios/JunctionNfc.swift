import Foundation
import CoreNFC

// CDVPlugin / CDVInvokedUrlCommand / CDVPluginResult resolved via the app's
// Cordova bridging header — do NOT `import Cordova` (see project convention).
@objc(JunctionNfc) class JunctionNfc: CDVPlugin {

    // Kept-alive callback registered by addNdefListener. Every detected tag is
    // delivered here so the JS wrapper stays unchanged across platforms.
    private var ndefCallbackId: String?
    private var nfcSession: AnyObject?

    // MARK: - registerNdef

    @objc(registerNdef:)
    func registerNdef(command: CDVInvokedUrlCommand) {
        ndefCallbackId = command.callbackId
        // Registration ack with no tag payload; keep the channel open.
        let result = CDVPluginResult(status: CDVCommandStatus_OK)
        result?.setKeepCallbackAs(true)
        commandDelegate.send(result, callbackId: command.callbackId)
    }

    // MARK: - beginSession

    @objc(beginSession:)
    func beginSession(command: CDVInvokedUrlCommand) {
        guard #available(iOS 13.0, *), NFCNDEFReaderSession.readingAvailable else {
            let result = CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: "NFC not available")
            commandDelegate.send(result, callbackId: command.callbackId)
            return
        }
        let session = NFCNDEFReaderSession(delegate: self, queue: nil, invalidateAfterFirstRead: true)
        session.alertMessage = "Hold your phone near the JCode™"
        nfcSession = session
        session.begin()

        // Resolve the beginSession call immediately; tag data and read errors
        // flow through the registered ndef listener / system NFC UI.
        let result = CDVPluginResult(status: CDVCommandStatus_OK)
        commandDelegate.send(result, callbackId: command.callbackId)
    }

    // MARK: - Helpers

    private func bytesToIntArray(_ data: Data) -> [Int] {
        return data.map { Int($0) }
    }

    @available(iOS 11.0, *)
    private func serialize(messages: [NFCNDEFMessage]) -> [String: Any] {
        var jsMessage: [[String: Any]] = []
        // The JS wrapper reads tag.ndefMessage[0].payload — flatten all records
        // of all detected messages into a single ndefMessage array.
        for message in messages {
            for record in message.records {
                jsMessage.append([
                    "tnf": Int(record.typeNameFormat.rawValue),
                    "type": bytesToIntArray(record.type),
                    "id": bytesToIntArray(record.identifier),
                    "payload": bytesToIntArray(record.payload)
                ])
            }
        }
        return ["tag": ["ndefMessage": jsMessage]]
    }
}

// MARK: - NFCNDEFReaderSessionDelegate

@available(iOS 11.0, *)
extension JunctionNfc: NFCNDEFReaderSessionDelegate {

    func readerSessionDidBecomeActive(_ session: NFCNDEFReaderSession) {}

    func readerSession(_ session: NFCNDEFReaderSession, didDetectNDEFs messages: [NFCNDEFMessage]) {
        guard let callbackId = ndefCallbackId else {
            session.invalidate()
            return
        }
        let payload = serialize(messages: messages)
        let result = CDVPluginResult(status: CDVCommandStatus_OK, messageAs: payload)
        result?.setKeepCallbackAs(true)
        commandDelegate.send(result, callbackId: callbackId)
        session.invalidate()
    }

    func readerSession(_ session: NFCNDEFReaderSession, didInvalidateWithError error: Error) {
        // Session is finished; tag delivery (if any) already happened via
        // didDetectNDEFs. Errors are surfaced by the system NFC UI.
        nfcSession = nil
    }
}
