package com.example.pastedtmf;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final int RC_CALL = 1;
    private static final int RC_PHONE = 2;

    private EditText et;
    private TextView tvStatus;
    private CheckBox cbCallOnly;
    private boolean autoPasted;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        et = (EditText) findViewById(R.id.et_input);
        tvStatus = (TextView) findViewById(R.id.tv_status);
        cbCallOnly = (CheckBox) findViewById(R.id.cb_call_only);
        cbCallOnly.setChecked(getSharedPreferences(FloatingService.PREFS, MODE_PRIVATE)
                .getBoolean(FloatingService.KEY_CALL_ONLY, true));

        findViewById(R.id.btn_paste).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pasteFromClipboard(true); }
        });
        findViewById(R.id.btn_clear).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { et.setText(""); }
        });
        findViewById(R.id.btn_pause).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { insert(","); }
        });
        findViewById(R.id.btn_wait).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { insert(";"); }
        });
        findViewById(R.id.btn_dial).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dial(false); }
        });
        findViewById(R.id.btn_call).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dial(true); }
        });
        findViewById(R.id.btn_start).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startOverlay(); }
        });
        findViewById(R.id.btn_stop).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { stopService(new Intent(MainActivity.this, FloatingService.class)); }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean ok = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this);
        tvStatus.setText(ok ? "Overlay permission: granted ✓" : "Overlay permission: NOT granted (tap Start bubble)");
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // Clipboard is readable here (we are the focused app): pre-fill once if empty.
        if (hasFocus && !autoPasted && et.getText().length() == 0) {
            autoPasted = true;
            pasteFromClipboard(false);
        }
    }

    // ------------------------------------------------------------ clipboard

    private void pasteFromClipboard(boolean toast) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        ClipData cd = cm == null ? null : cm.getPrimaryClip();
        String s = "";
        if (cd != null && cd.getItemCount() > 0) {
            s = FloatingService.sanitize(cd.getItemAt(0).coerceToText(this));
        }
        if (s.isEmpty()) {
            if (toast) Toast.makeText(this, "Clipboard has no number", Toast.LENGTH_SHORT).show();
            return;
        }
        et.setText(s);
        et.setSelection(s.length());
    }

    private void insert(String ch) {
        int st = Math.max(et.getSelectionStart(), 0);
        et.getText().insert(st, ch);
    }

    // ------------------------------------------------------------ dialing

    private void dial(boolean call) {
        String n = FloatingService.sanitize(et.getText());
        if (n.isEmpty()) {
            Toast.makeText(this, "Enter or paste a number first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (call && Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CALL_PHONE}, RC_CALL);
            return;
        }
        // '#' must be percent-encoded in a tel: URI. ',' and ';' stay as-is (pause / wait).
        Uri uri = Uri.parse("tel:" + n.replace("#", "%23"));
        try {
            startActivity(new Intent(call ? Intent.ACTION_CALL : Intent.ACTION_DIAL, uri));
        } catch (Exception e) {
            Toast.makeText(this, "Could not start call: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------ overlay

    private boolean hasPhoneStatePerm() {
        return Build.VERSION.SDK_INT < 23
                || checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED;
    }

    private void startOverlay() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow \"Display over other apps\", then press Start again", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        if (!hasPhoneStatePerm()) {
            requestPermissions(new String[]{Manifest.permission.READ_PHONE_STATE}, RC_PHONE);
            return;
        }
        doStart();
    }

    private void doStart() {
        // Without READ_PHONE_STATE we cannot detect calls, so "only during calls" would hide it forever.
        boolean callOnly = cbCallOnly.isChecked() && hasPhoneStatePerm();
        getSharedPreferences(FloatingService.PREFS, MODE_PRIVATE)
                .edit().putBoolean(FloatingService.KEY_CALL_ONLY, callOnly).apply();

        Intent i = new Intent(this, FloatingService.class);
        i.setAction(FloatingService.ACTION_SET_TEXT);
        String n = FloatingService.sanitize(et.getText());
        if (!n.isEmpty()) i.putExtra(FloatingService.EXTRA_TEXT, n);

        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);

        Toast.makeText(this, callOnly ? "Bubble will appear during calls" : "Bubble started", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onRequestPermissionsResult(int rc, String[] perms, int[] res) {
        super.onRequestPermissionsResult(rc, perms, res);
        boolean granted = res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED;
        if (rc == RC_CALL && granted) dial(true);
        else if (rc == RC_PHONE) doStart();
    }
}
