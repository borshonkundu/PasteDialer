package com.example.pastedtmf;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.widget.Toast;

/**
 * Invisible activity. On Android 10+ only the focused window can read the clipboard,
 * so we wait for window focus, read it, hand it to the service and finish immediately.
 */
public class PasteActivity extends Activity {
    private boolean done;

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus || done) return;
        done = true;

        String digits = "";
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        ClipData cd = cm == null ? null : cm.getPrimaryClip();
        if (cd != null && cd.getItemCount() > 0) {
            digits = FloatingService.sanitize(cd.getItemAt(0).coerceToText(this));
        }
        if (digits.isEmpty()) {
            Toast.makeText(this, "No number found in clipboard", Toast.LENGTH_SHORT).show();
        } else {
            Intent i = new Intent(this, FloatingService.class);
            i.setAction(FloatingService.ACTION_SET_TEXT);
            i.putExtra(FloatingService.EXTRA_TEXT, digits);
            try { startService(i); } catch (Exception ignored) { }
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
