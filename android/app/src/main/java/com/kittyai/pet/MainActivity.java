package com.kittyai.pet;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private AppPrefs prefs;
    private SecretStore secrets;
    private DeepSeekClient client;
    private LinearLayout messages;
    private ScrollView messageScroll;
    private EditText input;
    private Button sendButton;
    private Button overlayButton;
    private TextView status;
    private PetAnimator animator;
    private LinearLayout composer;
    private int keyboardOffset;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                        | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        prefs = new AppPrefs(this);
        secrets = new SecretStore(this);
        client = new DeepSeekClient(this);
        setContentView(buildUi());
        renderHistory();
        requestNotificationPermission();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshOverlayButton();
        if (animator != null) animator.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (animator != null) animator.stop();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private View buildUi() {
        int padding = dp(18);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(padding, dp(12), padding, dp(8));
        root.setBackgroundColor(Color.rgb(255, 244, 247));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("Kitty AI");
        title.setTextColor(Color.rgb(53, 43, 49));
        title.setTextSize(25);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));

        Button settingsButton = button("设置");
        settingsButton.setOnClickListener(v -> showSettings());
        header.addView(settingsButton, new LinearLayout.LayoutParams(dp(88), dp(44)));
        root.addView(header);

        ImageView pet = new ImageView(this);
        pet.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(pet, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(145)));
        animator = new PetAnimator(this, pet);

        status = new TextView(this);
        status.setText("随时可以聊聊");
        status.setGravity(Gravity.CENTER);
        status.setTextColor(Color.rgb(214, 47, 95));
        status.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(28)));

        overlayButton = button("开启悬浮桌宠");
        overlayButton.setOnClickListener(v -> toggleOverlay());
        root.addView(overlayButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        messageScroll = new ScrollView(this);
        messageScroll.setFillViewport(true);
        messageScroll.setClipToPadding(false);
        messages = new LinearLayout(this);
        messages.setOrientation(LinearLayout.VERTICAL);
        messages.setPadding(0, dp(10), 0, dp(10));
        messageScroll.addView(messages, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(messageScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        composer = new LinearLayout(this);
        composer.setGravity(Gravity.BOTTOM);
        composer.setPadding(0, dp(8), 0, 0);
        input = new EditText(this);
        input.setHint("和 Kitty 说点什么…");
        input.setTextSize(16);
        input.setMinLines(1);
        input.setMaxLines(4);
        input.setSingleLine(false);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        input.setRawInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                send();
                return true;
            }
            return false;
        });
        input.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) scrollToBottomSoon();
        });
        composer.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        sendButton = button("发送");
        sendButton.setOnClickListener(v -> send());
        LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(dp(76), dp(56));
        sendParams.setMargins(dp(8), 0, 0, 0);
        composer.addView(sendButton, sendParams);
        root.addView(composer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        installKeyboardAvoidance(root);
        return root;
    }

    private void installKeyboardAvoidance(View root) {
        root.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            Rect visible = new Rect();
            root.getWindowVisibleDisplayFrame(visible);
            int fullHeight = root.getRootView().getHeight();
            int hiddenHeight = Math.max(0, fullHeight - visible.bottom);
            int nextOffset = hiddenHeight > dp(120) ? hiddenHeight : 0;
            if (nextOffset == keyboardOffset) return;
            keyboardOffset = nextOffset;
            if (composer != null) {
                composer.setTranslationY(-keyboardOffset);
            }
            if (messageScroll != null) {
                messageScroll.setPadding(0, 0, 0, keyboardOffset + dp(12));
            }
            scrollToBottomSoon();
        });
    }

    private void renderHistory() {
        messages.removeAllViews();
        List<ChatMessage> history = prefs.getHistory();
        if (history.isEmpty()) {
            addMessage("assistant", "我搬到手机上啦。开启悬浮桌宠后，点我就能回来聊天。");
        } else {
            for (ChatMessage item : history) addMessage(item.role, item.content);
        }
    }

    private void addMessage(String role, String text) {
        TextView bubble = new TextView(this);
        bubble.setText(text);
        bubble.setTextSize(16);
        bubble.setLineSpacing(0, 1.12f);
        bubble.setPadding(dp(12), dp(9), dp(12), dp(9));
        bubble.setTextColor("user".equals(role) ? Color.WHITE : Color.rgb(53, 43, 49));
        bubble.setBackgroundResource("user".equals(role)
                ? R.drawable.bubble_user : R.drawable.bubble_kitty);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.gravity = "user".equals(role) ? Gravity.END : Gravity.START;
        params.setMargins("user".equals(role) ? dp(54) : 0, dp(5),
                "user".equals(role) ? 0 : dp(54), dp(5));
        messages.addView(bubble, params);
        scrollToBottomSoon();
    }

    private void scrollToBottomSoon() {
        if (messageScroll == null) return;
        messageScroll.postDelayed(() -> messageScroll.fullScroll(View.FOCUS_DOWN), 80);
        messageScroll.postDelayed(() -> messageScroll.fullScroll(View.FOCUS_DOWN), 220);
    }

    private void send() {
        String text = input.getText().toString().trim();
        if (text.isEmpty() || !sendButton.isEnabled()) return;
        input.setText("");
        addMessage("user", text);
        setBusy(true);

        executor.execute(() -> {
            try {
                String answer = client.chat(text);
                runOnUiThread(() -> {
                    addMessage("assistant", answer);
                    animator.play("jumping", 1);
                    setBusy(false);
                    input.requestFocus();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    addMessage("assistant", "连接失败：" + error.getMessage());
                    animator.play("failed", 1);
                    setBusy(false);
                    input.requestFocus();
                });
            }
        });
    }

    private void setBusy(boolean busy) {
        sendButton.setEnabled(!busy);
        input.setEnabled(!busy);
        status.setText(busy ? "Kitty 正在想…" : "随时可以聊聊");
        if (busy) animator.play("running", -1);
    }

    private void toggleOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Intent permission = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(permission);
            Toast.makeText(this, "请允许 Kitty 显示在其他应用上层", Toast.LENGTH_LONG).show();
            return;
        }
        if (OverlayPetService.isRunning()) {
            stopService(new Intent(this, OverlayPetService.class));
        } else {
            startForegroundService(new Intent(this, OverlayPetService.class));
        }
        overlayButton.postDelayed(this::refreshOverlayButton, 250);
    }

    private void refreshOverlayButton() {
        if (overlayButton == null) return;
        if (!Settings.canDrawOverlays(this)) {
            overlayButton.setText("授权悬浮窗");
        } else if (OverlayPetService.isRunning()) {
            overlayButton.setText("关闭悬浮桌宠");
        } else {
            overlayButton.setText("开启悬浮桌宠");
        }
    }

    private void showSettings() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(8), dp(20), dp(12));

        TextView keyLabel = label("DeepSeek API 密钥");
        panel.addView(keyLabel);
        EditText apiKey = new EditText(this);
        apiKey.setHint(secrets.getApiKey().isEmpty() ? "sk-..." : "已保存；留空则不修改");
        apiKey.setSingleLine(true);
        apiKey.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        apiKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        panel.addView(apiKey);

        TextView personalityLabel = label("Kitty 性格");
        panel.addView(personalityLabel);
        EditText personality = new EditText(this);
        personality.setText(prefs.getPersonality());
        personality.setGravity(Gravity.TOP);
        personality.setMinLines(8);
        personality.setMaxLines(12);
        personality.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        panel.addView(personality);

        CheckBox autoStart = new CheckBox(this);
        autoStart.setText("手机重启后自动恢复桌宠");
        autoStart.setChecked(prefs.isAutoStartEnabled());
        panel.addView(autoStart);

        LinearLayout memoryTools = new LinearLayout(this);
        memoryTools.setOrientation(LinearLayout.VERTICAL);
        memoryTools.setPadding(0, dp(10), 0, 0);
        Button viewMemory = button("查看长期记忆");
        viewMemory.setOnClickListener(v -> showInfoDialog("长期记忆", prefs.getLongMemoryDisplay()));
        memoryTools.addView(viewMemory, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        Button clearMemory = button("清空长期记忆");
        clearMemory.setOnClickListener(v -> {
            prefs.clearLongMemory();
            Toast.makeText(this, "长期记忆已清空", Toast.LENGTH_SHORT).show();
        });
        memoryTools.addView(clearMemory, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        Button viewPersonalityDelta = button("查看性格学习");
        viewPersonalityDelta.setOnClickListener(v -> showInfoDialog("性格学习", prefs.getPersonalityDeltaDisplay()));
        memoryTools.addView(viewPersonalityDelta, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        Button clearPersonalityDelta = button("清空性格学习");
        clearPersonalityDelta.setOnClickListener(v -> {
            prefs.clearPersonalityDelta();
            Toast.makeText(this, "性格学习已清空", Toast.LENGTH_SHORT).show();
        });
        memoryTools.addView(clearPersonalityDelta, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        panel.addView(memoryTools);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(panel);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Kitty 设置")
                .setView(scroll)
                .setNeutralButton("清空聊天", (ignoredDialog, which) -> {
                    prefs.clearHistory();
                    renderHistory();
                })
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (ignoredDialog, which) -> {
                    prefs.setPersonality(personality.getText().toString());
                    prefs.setAutoStartEnabled(autoStart.isChecked());
                    String newKey = apiKey.getText().toString().trim();
                    if (!newKey.isEmpty()) {
                        try {
                            secrets.saveApiKey(newKey);
                        } catch (Exception error) {
                            Toast.makeText(this, "密钥保存失败：" + error.getMessage(),
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                    Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show();
                })
                .create();
        dialog.setOnShowListener(d -> {
            if (dialog.getWindow() != null) {
                dialog.getWindow().setSoftInputMode(
                        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                                | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
            }
        });
        dialog.show();
    }

    private void showInfoDialog(String title, String text) {
        TextView content = new TextView(this);
        content.setText(text);
        content.setTextIsSelectable(true);
        content.setTextSize(14);
        content.setPadding(dp(18), dp(12), dp(18), dp(12));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(scroll)
                .setPositiveButton("关闭", null)
                .show();
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
        }
    }

    private Button button(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(14);
        button.setTextColor(Color.rgb(214, 47, 95));
        return button;
    }

    private TextView label(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(Color.rgb(53, 43, 49));
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setPadding(0, dp(12), 0, 0);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
