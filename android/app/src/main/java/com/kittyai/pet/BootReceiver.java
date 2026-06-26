package com.kittyai.pet;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        AppPrefs prefs = new AppPrefs(context);
        if (prefs.isAutoStartEnabled() && Settings.canDrawOverlays(context)) {
            context.startForegroundService(new Intent(context, OverlayPetService.class));
        }
    }
}
