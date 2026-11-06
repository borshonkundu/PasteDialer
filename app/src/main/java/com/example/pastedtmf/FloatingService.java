package com.example.pastedtmf;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * Lightweight overlay: a draggable bubble that expands into a small panel showing the
 * pasted digits one-by-one. No polling, no timers; everything is event driven.
 */
public class FloatingService extends Service {

    public static final String ACTION_SET_TEXT = "com.example.pastedtmf.SET_TEXT";
    public static final String EXTRA_TEXT = "text";
    public static final String PREFS = "prefs";
    public static final String KEY_CALL_ONLY = "call_only";

    private static final String CHANNEL_ID = "overlay";
    private static final int NOTIF_ID = 1;

    private WindowManager wm;
    private ClipboardManager cm;
    private ToneGenerator tone;

    private View bubble, panel;
    private WindowManager.LayoutParams bubbleLp, panelLp;
    private boolean bubbleAttached, panelAttached;
    private TextView tvSeq, tvCurrent, tvProgress;

    private String seq = "";
    private int pos = 0;
    private boolean inCall = false;
    private boolean callOnly = false;
    private boolean panelOpen = false;
    private boolean ready = false;

    /** Keeps digits and dial characters only; converts any-script digits (e.g. Bengali) to ASCII. */
    public static String sanitize(CharSequence in) {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder(in.length());
        for (int i = 0; i < in.length(); i++) {
            char c = in.charAt(i);
            int d = Character.digit(c, 10);
            if (d >= 0) sb.append((char) ('0' + d));
            else if (c == '*' || c == '#' || c == '+' || c == ',' || c == ';') sb.append(c);
        }
        return sb.toString();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // Must call startForeground quickly on Android 8+ when started via startForegroundService
        startForeground(NOTIF_ID, buildNotification());

        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            stopSelf();
            return;
        }

        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);

        LayoutInflater inf = LayoutInflater.from(this);
        bubble = inf.inflate(R.layout.overlay_bubble, null);
        panel = inf.inflate(R.layout.overlay_panel, null);

        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        // NOT_FOCUSABLE: never steals focus/keyboard from the call screen.
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;

        bubbleLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                type, flags, PixelFormat.TRANSLUCENT);
        bubbleLp.gravity = Gravity.TOP | Gravity.START;
        bubbleLp.x = dp(8);
        bubbleLp.y = dp(160);

        DisplayMetrics dm = getResources().getDisplayMetrics();
        panelLp = new WindowManager.LayoutParams(
                Math.min(dm.widthPixels - dp(16), dp(380)), WindowManager.LayoutParams.WRAP_CONTENT,
                type, flags, PixelFormat.TRANSLUCENT);
        panelLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        panelLp.y = dp(48);   // top of screen: keeps the in-call keypad uncovered

        bubble.setOnTouchListener(new Drag(bubble, bubbleLp, new Runnable() {
            @Override public void run() { panelOpen = true; applyVisibility(); }
        }));
        panel.findViewById(R.id.header).setOnTouchListener(new Drag(panel, panelLp, null));

        tvSeq = (TextView) panel.findViewById(R.id.tv_seq);
        tvCurrent = (TextView) panel.findViewById(R.id.tv_current);
        tvProgress = (TextView) panel.findViewById(R.id.tv_progress);

        panel.findViewById(R.id.btn_min).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { panelOpen = false; applyVisibility(); }
        });
        panel.findViewById(R.id.btn_close).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { stopSelf(); }
        });
        panel.findViewById(R.id.btn_next).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (pos < seq.length() - 1) { pos++; refresh(); } }
        });
        panel.findViewById(R.id.btn_prev).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (pos > 0) { pos--; refresh(); } }
        });
        panel.findViewById(R.id.btn_reset).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pos = 0; refresh(); }
        });
        panel.findViewById(R.id.btn_clear).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setSeq(""); }
        });
        panel.findViewById(R.id.btn_tone).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { playTone(); }
        });
        panel.findViewById(R.id.btn_paste).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // Android 10+ only allows clipboard reads from a focused window,
                // so briefly open an invisible activity to do it.
                Intent i = new Intent(FloatingService.this, PasteActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
                try { startActivity(i); } catch (Exception ignored) { }
            }
        });

        registerReceiver(phoneRx, new IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED));
        if (cm != null) cm.addPrimaryClipChangedListener(clipListener); // fires in background on Android < 10

        try {
            TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
            inCall = tm != null && tm.getCallState() != TelephonyManager.CALL_STATE_IDLE;
        } catch (SecurityException ignored) { }

        ready = true;
        refresh();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!ready) return START_NOT_STICKY;
        if (intent != null && ACTION_SET_TEXT.equals(intent.getAction())) {
            String t = intent.getStringExtra(EXTRA_TEXT);
            if (t != null) setSeq(sanitize(t));
        }
        callOnly = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_CALL_ONLY, false);
        applyVisibility();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (ready) {
            try { unregisterReceiver(phoneRx); } catch (Exception ignored) { }
            if (cm != null) cm.removePrimaryClipChangedListener(clipListener);
            setBubbleAttached(false);
            setPanelAttached(false);
        }
        if (tone != null) { tone.release(); tone = null; }
        super.onDestroy();
    }

    // ---------------------------------------------------------------- state

    private void setSeq(String s) {
        seq = s;
        pos = 0;
        refresh();
    }

    private void refresh() {
        if (tvSeq == null) return;
        if (seq.isEmpty()) {
            tvSeq.setText(R.string.empty_hint);
            tvCurrent.setText("–");
            tvProgress.setText("0 / 0");
            return;
        }
        SpannableString s = new SpannableString(seq);
        s.setSpan(new BackgroundColorSpan(0xFF00E676), pos, pos + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new ForegroundColorSpan(0xFF000000), pos, pos + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        tvSeq.setText(s);
        tvCurrent.setText(String.valueOf(seq.charAt(pos)));
        tvProgress.setText((pos + 1) + " / " + seq.length());
    }

    private void applyVisibility() {
        boolean show = !callOnly || inCall;
        setBubbleAttached(show && !panelOpen);
        setPanelAttached(show && panelOpen);
    }

    private void setBubbleAttached(boolean want) {
        try {
            if (want && !bubbleAttached) { wm.addView(bubble, bubbleLp); bubbleAttached = true; }
            else if (!want && bubbleAttached) { wm.removeView(bubble); bubbleAttached = false; }
        } catch (Exception ignored) { }
    }

    private void setPanelAttached(boolean want) {
        try {
            if (want && !panelAttached) { wm.addView(panel, panelLp); panelAttached = true; }
            else if (!want && panelAttached) { wm.removeView(panel); panelAttached = false; }
        } catch (Exception ignored) { }
    }

    /**
     * Plays the tone LOCALLY. It is NOT injected into the call audio uplink
     * (Android does not allow that). Only useful for testing / speakerphone experiments.
     */
    private void playTone() {
        if (seq.isEmpty()) return;
        char c = seq.charAt(pos);
        int t;
        if (c >= '0' && c <= '9') t = ToneGenerator.TONE_DTMF_0 + (c - '0');
        else if (c == '*') t = ToneGenerator.TONE_DTMF_S;
        else if (c == '#') t = ToneGenerator.TONE_DTMF_P;
        else return;
        try {
            if (tone == null) tone = new ToneGenerator(AudioManager.STREAM_DTMF, 100);
            tone.startTone(t, 150);
        } catch (RuntimeException ignored) { }
    }

    // ---------------------------------------------------------------- receivers

    private final BroadcastReceiver phoneRx = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent i) {
            String st = i.getStringExtra(TelephonyManager.EXTRA_STATE);
            inCall = TelephonyManager.EXTRA_STATE_OFFHOOK.equals(st)
                    || TelephonyManager.EXTRA_STATE_RINGING.equals(st);
            applyVisibility();
        }
    };

    private final ClipboardManager.OnPrimaryClipChangedListener clipListener =
            new ClipboardManager.OnPrimaryClipChangedListener() {
                @Override public void onPrimaryClipChanged() {
                    try {
                        ClipData cd = cm.getPrimaryClip();
                        if (cd == null || cd.getItemCount() == 0) return;
                        CharSequence t = cd.getItemAt(0).coerceToText(FloatingService.this);
                        if (t == null || t.length() > 64) return;
                        String s = sanitize(t);
                        if (s.length() >= 3) setSeq(s);
                    } catch (Exception ignored) { }
                }
            };

    // ---------------------------------------------------------------- helpers

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, "Overlay", NotificationManager.IMPORTANCE_MIN));
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), piFlags);
        return b.setSmallIcon(android.R.drawable.sym_action_call)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(R.string.notif_text))
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    /** Drag a window by touching a view; short tap without movement triggers onClick. */
    private class Drag implements View.OnTouchListener {
        private final View target;
        private final WindowManager.LayoutParams lp;
        private final Runnable onClick;
        private int startX, startY;
        private float downX, downY;
        private boolean moved;

        Drag(View target, WindowManager.LayoutParams lp, Runnable onClick) {
            this.target = target; this.lp = lp; this.onClick = onClick;
        }

        @Override public boolean onTouch(View v, MotionEvent e) {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startX = lp.x; startY = lp.y;
                    downX = e.getRawX(); downY = e.getRawY();
                    moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                    if (!moved && Math.abs(dx) + Math.abs(dy) > dp(6)) moved = true;
                    if (moved) {
                        lp.x = startX + (int) dx;
                        lp.y = startY + (int) dy;
                        try { wm.updateViewLayout(target, lp); } catch (Exception ignored) { }
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!moved && onClick != null) onClick.run();
                    return true;
            }
            return false;
        }
    }
}
