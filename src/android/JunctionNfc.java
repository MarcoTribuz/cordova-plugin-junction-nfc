package com.junction.plugins;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.os.Build;
import android.os.Parcelable;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Junction custom NFC reader (Android).
 *
 * Reads NDEF tags via foreground dispatch while the app is in the foreground
 * and forwards each tag to the JS listener registered with addNdefListener.
 * Drop-in for the phonegap-nfc subset the app consumes — see www/nfc.js.
 */
public class JunctionNfc extends CordovaPlugin {

    // Kept-alive callback from registerNdef; fired once per detected tag.
    private CallbackContext ndefCallback;
    private NfcAdapter nfcAdapter;
    private PendingIntent pendingIntent;

    @Override
    public void initialize(CordovaInterface cordova, CordovaWebView webView) {
        super.initialize(cordova, webView);
        Activity activity = cordova.getActivity();
        nfcAdapter = NfcAdapter.getDefaultAdapter(activity);

        Intent intent = new Intent(activity, activity.getClass());
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = 0;
        // API 31+ requires an explicit mutability flag; NFC adds extras → MUTABLE.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags = PendingIntent.FLAG_MUTABLE;
        }
        pendingIntent = PendingIntent.getActivity(activity, 0, intent, flags);
    }

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) {
        switch (action) {
            case "registerNdef":
                ndefCallback = callbackContext;
                // Registration ack; keep the channel open for tag events.
                PluginResult ack = new PluginResult(PluginResult.Status.OK);
                ack.setKeepCallback(true);
                callbackContext.sendPluginResult(ack);
                return true;
            case "beginSession":
                // iOS-only concept; Android reads via foreground dispatch.
                callbackContext.success();
                return true;
            case "removeNdef":
                ndefCallback = null;
                callbackContext.success();
                return true;
            default:
                return false;
        }
    }

    @Override
    public void onResume(boolean multitasking) {
        super.onResume(multitasking);
        if (nfcAdapter == null) return;
        // null filters / techLists → catch every tag while in the foreground.
        nfcAdapter.enableForegroundDispatch(cordova.getActivity(), pendingIntent, null, null);
    }

    @Override
    public void onPause(boolean multitasking) {
        super.onPause(multitasking);
        if (nfcAdapter == null) return;
        nfcAdapter.disableForegroundDispatch(cordova.getActivity());
    }

    @Override
    public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent == null || ndefCallback == null) return;
        String action = intent.getAction();
        if (!NfcAdapter.ACTION_NDEF_DISCOVERED.equals(action)
                && !NfcAdapter.ACTION_TECH_DISCOVERED.equals(action)
                && !NfcAdapter.ACTION_TAG_DISCOVERED.equals(action)) {
            return;
        }

        Parcelable[] rawMessages;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Typed variant: untyped getParcelableArrayExtra(String) is deprecated since API 33.
            rawMessages = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, NdefMessage.class);
        } else {
            rawMessages = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES);
        }
        if (rawMessages == null || rawMessages.length == 0) return;

        try {
            JSONObject event = buildTagEvent(rawMessages);
            PluginResult result = new PluginResult(PluginResult.Status.OK, event);
            result.setKeepCallback(true);
            ndefCallback.sendPluginResult(result);
        } catch (JSONException e) {
            // Malformed tag — ignore; the JS layer extracts the code via regex.
        }
    }

    /** Builds { tag: { ndefMessage: [ { tnf, type, id, payload } ] } }. */
    private JSONObject buildTagEvent(Parcelable[] rawMessages) throws JSONException {
        JSONArray ndefMessage = new JSONArray();
        for (Parcelable raw : rawMessages) {
            NdefMessage message = (NdefMessage) raw;
            for (NdefRecord record : message.getRecords()) {
                JSONObject jsRecord = new JSONObject();
                jsRecord.put("tnf", record.getTnf());
                jsRecord.put("type", bytesToJSON(record.getType()));
                jsRecord.put("id", bytesToJSON(record.getId()));
                jsRecord.put("payload", bytesToJSON(record.getPayload()));
                ndefMessage.put(jsRecord);
            }
        }
        JSONObject tag = new JSONObject();
        tag.put("ndefMessage", ndefMessage);
        JSONObject event = new JSONObject();
        event.put("tag", tag);
        return event;
    }

    private JSONArray bytesToJSON(byte[] bytes) {
        JSONArray array = new JSONArray();
        if (bytes != null) {
            for (byte b : bytes) {
                array.put(b & 0xff);
            }
        }
        return array;
    }
}
