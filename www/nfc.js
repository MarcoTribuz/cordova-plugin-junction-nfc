/**
 * Junction custom NFC reader — www bridge.
 *
 * Drop-in replacement for the subset of phonegap-nfc consumed by the app
 * (see imports/ui/plugins/nfc.js). Clobbers the global `nfc` object so no
 * client code changes are required.
 *
 * Exposed members:
 *   nfc.addNdefListener(callback, win, fail) — persistent NDEF listener.
 *       `callback` receives { tag: { ndefMessage: [ { tnf, type, id, payload } ] } }.
 *   nfc.beginSession(win, fail)             — iOS only: opens the system
 *       NFCNDEFReaderSession modal. No-op success on Android.
 *   nfc.bytesToString(bytes)                — sync UTF-8 decode of a byte array.
 */

var exec = require('cordova/exec');

var SERVICE = 'JunctionNfc';

function Nfc() {}

/**
 * Registers a persistent NDEF listener. Native keeps the callback alive and
 * fires it once per detected tag.
 * @param {function(Object): void} callback - invoked with the tag event
 * @param {function=} win  - invoked once when the listener is registered
 * @param {function=} fail - invoked on registration / read error
 */
Nfc.prototype.addNdefListener = function (callback, win, fail) {
  var first = true;
  exec(function (event) {
    // First native callback is the registration ack (no tag payload).
    if (first) {
      first = false;
      if (typeof win === 'function') win();
      // The ack carries no `tag`; only forward real tag events below.
      if (!event || !event.tag) return;
    }
    if (typeof callback === 'function') callback(event);
  }, fail, SERVICE, 'registerNdef', []);
};

/**
 * iOS only: opens the system NFC scan modal. On Android NFC tags are delivered
 * automatically via foreground dispatch while the app is in the foreground, so
 * this resolves immediately.
 */
Nfc.prototype.beginSession = function (win, fail) {
  exec(win, fail, SERVICE, 'beginSession', []);
};

/**
 * Decodes a byte array to a UTF-8 string. Pure JS, no native call.
 * Mirrors phonegap-nfc's util.bytesToString. The NDEF text-record status/lang
 * prefix bytes remain as leading noise — irrelevant because the consumer
 * extracts the 15-char code via regex (see Scan.handleNfcTag).
 * @param {number[]} bytes
 * @returns {string}
 */
Nfc.prototype.bytesToString = function (bytes) {
  if (!bytes) return '';
  var result = '';
  var i, c, c1, c2, c3;
  i = c = c1 = c2 = c3 = 0;

  while (i < bytes.length) {
    c = bytes[i] & 0xff;
    if (c < 128) {
      result += String.fromCharCode(c);
      i++;
    } else if (c > 191 && c < 224) {
      if (i + 1 >= bytes.length) throw new Error('Un-expected end of data');
      c2 = bytes[i + 1] & 0xff;
      result += String.fromCharCode(((c & 31) << 6) | (c2 & 63));
      i += 2;
    } else {
      if (i + 2 >= bytes.length) throw new Error('Un-expected end of data');
      c2 = bytes[i + 1] & 0xff;
      c3 = bytes[i + 2] & 0xff;
      result += String.fromCharCode(((c & 15) << 12) | ((c2 & 63) << 6) | (c3 & 63));
      i += 3;
    }
  }
  return result;
};

module.exports = new Nfc();
