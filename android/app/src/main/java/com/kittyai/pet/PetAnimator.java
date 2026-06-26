package com.kittyai.pet;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

public final class PetAnimator {
    private static final Map<String, Integer> COUNTS = new HashMap<>();
    private static final Map<String, Long> DELAYS = new HashMap<>();

    static {
        COUNTS.put("idle", 6);
        COUNTS.put("running-right", 8);
        COUNTS.put("running-left", 8);
        COUNTS.put("waving", 4);
        COUNTS.put("jumping", 5);
        COUNTS.put("failed", 8);
        COUNTS.put("waiting", 6);
        COUNTS.put("running", 6);
        COUNTS.put("review", 6);

        DELAYS.put("idle", 360L);
        DELAYS.put("running-right", 85L);
        DELAYS.put("running-left", 85L);
        DELAYS.put("waving", 170L);
        DELAYS.put("jumping", 125L);
        DELAYS.put("failed", 190L);
        DELAYS.put("waiting", 230L);
        DELAYS.put("running", 170L);
        DELAYS.put("review", 190L);
    }

    private final Context context;
    private final ImageView imageView;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();

    private String state = "idle";
    private int frame;
    private int cyclesDone;
    private int requestedCycles = -1;
    private boolean running;

    public PetAnimator(Context context, ImageView imageView) {
        this.context = context.getApplicationContext();
        this.imageView = imageView;
    }

    public void start() {
        if (running) return;
        running = true;
        handler.post(tick);
    }

    public void stop() {
        running = false;
        handler.removeCallbacksAndMessages(null);
    }

    public void play(String newState, int cycles) {
        if (!COUNTS.containsKey(newState)) return;
        state = newState;
        frame = 0;
        cyclesDone = 0;
        requestedCycles = cycles;
        if (!running) start();
    }

    public String getState() {
        return state;
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            setFrame(state, frame);
            frame++;
            if (frame >= COUNTS.get(state)) {
                frame = 0;
                cyclesDone++;
                if (requestedCycles >= 0 && cyclesDone >= requestedCycles) {
                    state = "idle";
                    cyclesDone = 0;
                    requestedCycles = -1;
                } else if ("idle".equals(state) && random.nextInt(18) == 0) {
                    String[] actions = {"waving", "jumping", "waiting", "review"};
                    state = actions[random.nextInt(actions.length)];
                    cyclesDone = 0;
                    requestedCycles = 1;
                }
            }
            long delay = DELAYS.get(state);
            if ("idle".equals(state) && frame == 0) delay += random.nextInt(500);
            handler.postDelayed(this, delay);
        }
    };

    private void setFrame(String animation, int index) {
        String path = "animations/" + animation + "/" + index + ".png";
        try (InputStream input = context.getAssets().open(path)) {
            Drawable drawable = Drawable.createFromStream(input, path);
            imageView.setImageDrawable(drawable);
        } catch (IOException ignored) {
        }
    }
}
