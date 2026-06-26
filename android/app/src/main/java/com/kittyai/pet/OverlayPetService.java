package com.kittyai.pet;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;

public final class OverlayPetService extends Service {
    private static final String CHANNEL_ID = "kitty_overlay";
    private static final int NOTIFICATION_ID = 7;
    private static final String ACTION_STOP = "com.kittyai.pet.STOP_OVERLAY";
    private static volatile boolean running;

    private WindowManager windowManager;
    private ImageView petView;
    private WindowManager.LayoutParams params;
    private PetAnimator animator;

    public static boolean isRunning() {
        return running;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        if (!Settings.canDrawOverlays(this)) {
            stopSelf();
            return;
        }
        createOverlay();
        running = true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (animator != null) animator.stop();
        if (petView != null && windowManager != null) {
            try {
                windowManager.removeView(petView);
            } catch (IllegalArgumentException ignored) {
            }
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createOverlay() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        petView = new ImageView(this);
        petView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        petView.setContentDescription("Kitty 悬浮桌宠");

        params = new WindowManager.LayoutParams(
                dp(150),
                dp(170),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = getResources().getDisplayMetrics().widthPixels - dp(170);
        params.y = getResources().getDisplayMetrics().heightPixels - dp(310);
        windowManager.addView(petView, params);

        animator = new PetAnimator(this, petView);
        animator.start();
        petView.setOnTouchListener(new DragListener());
    }

    private final class DragListener implements View.OnTouchListener {
        private int startX;
        private int startY;
        private float touchX;
        private float touchY;
        private boolean dragged;

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX = params.x;
                    startY = params.y;
                    touchX = event.getRawX();
                    touchY = event.getRawY();
                    dragged = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int dx = Math.round(event.getRawX() - touchX);
                    int dy = Math.round(event.getRawY() - touchY);
                    if (Math.abs(dx) + Math.abs(dy) > dp(5)) {
                        dragged = true;
                        String direction = dx >= 0 ? "running-right" : "running-left";
                        if (!direction.equals(animator.getState())) animator.play(direction, -1);
                    }
                    params.x = startX + dx;
                    params.y = startY + dy;
                    windowManager.updateViewLayout(petView, params);
                    return true;
                case MotionEvent.ACTION_UP:
                    if (dragged) {
                        animator.play("jumping", 1);
                    } else {
                        animator.play("waving", 1);
                        openChat();
                    }
                    return true;
                default:
                    return false;
            }
        }
    }

    private void openChat() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openIntent = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stop = new Intent(this, OverlayPetService.class).setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.kitty)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text))
                .setContentIntent(openIntent)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        null, "关闭桌宠", stopIntent).build())
                .build();
    }

    private void createChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("保持 Kitty 悬浮桌宠运行");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
