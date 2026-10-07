package com.hotelatgangnam.smsbridge;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Local-only setup screen. Slack credentials never leave this phone except to Slack APIs. */
public final class MainActivity extends Activity {
    private static final int PERMISSION_REQUEST = 702;

    private ConfigStore configStore;
    private EditText botToken;
    private EditText appToken;
    private EditText commandPrefix;
    private CheckBox autoReplyEnabled;
    private EditText autoReplyText;
    private EditText cooldownMinutes;
    private EditText maxAutoPerHour;
    private EditText maxTotalPerHour;
    private EditText blockedNumbers;
    private EditText allowedSlackUsers;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        configStore = new ConfigStore(this);
        setContentView(buildContent());
        loadSettings();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    private View buildContent() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(20);
        content.setPadding(padding, padding, padding, padding * 2);
        scroll.addView(content);

        TextView title = new TextView(this);
        title.setText("호텔 앳 강남 · 폰 SMS 브리지");
        title.setTextSize(24);
        title.setTextColor(Color.rgb(30, 77, 59));
        title.setPadding(0, 0, 0, dp(8));
        content.addView(title);

        TextView explanation = new TextView(this);
        explanation.setText(
                "이 Android 폰의 SMS를 "
                        + ConfigStore.REQUIRED_CHANNEL_NAME
                        + "과 연결합니다. PC Phone Link는 사용하지 않습니다.\n"
                        + "OTP·광고·자동발신·단순 감사는 자동회신하지 않으며, "
                        + "예약 변경·환불·안전 문의는 사람에게 남깁니다.");
        explanation.setTextSize(15);
        explanation.setPadding(0, 0, 0, dp(16));
        content.addView(explanation);

        status = new TextView(this);
        status.setTextSize(15);
        status.setPadding(dp(12), dp(10), dp(12), dp(10));
        status.setBackgroundColor(Color.rgb(238, 245, 241));
        content.addView(status);

        addLabel(content, "고정 Slack 채널");
        TextView channel = new TextView(this);
        channel.setText(
                ConfigStore.REQUIRED_CHANNEL_NAME
                        + "\n"
                        + ConfigStore.REQUIRED_CHANNEL_ID);
        channel.setTextSize(16);
        content.addView(channel);

        botToken = addTextField(content, "Slack 봇 토큰 (xoxb-…)", true, false);
        appToken = addTextField(content, "Slack 앱 토큰 (xapp-…, connections:write)", true, false);
        commandPrefix = addTextField(content, "새 발신 명령어", false, false);
        commandPrefix.setHint("!sms");

        allowedSlackUsers =
                addTextField(
                        content,
                        "발신 허용 Slack 사용자 ID (선택, 쉼표/줄바꿈; 비우면 채널 구성원 전체)",
                        false,
                        true);
        blockedNumbers =
                addTextField(
                        content, "차단 전화번호 (선택, 쉼표/줄바꿈)", false, true);

        autoReplyEnabled = new CheckBox(this);
        autoReplyEnabled.setText("FAQ/질문 자동회신 사용");
        autoReplyEnabled.setTextSize(16);
        autoReplyEnabled.setPadding(0, dp(14), 0, 0);
        content.addView(autoReplyEnabled);

        autoReplyText =
                addTextField(
                        content,
                        "FAQ에 해당하지 않는 일반 질문의 안전한 접수 회신",
                        false,
                        true);
        cooldownMinutes =
                addNumberField(content, "동일 발신자 자동회신 대기시간 (분)");
        maxAutoPerHour =
                addNumberField(content, "자동회신 시간당 최대 건수");
        maxTotalPerHour =
                addNumberField(content, "전체 발신 SMS 시간당 최대 건수");

        Button start = new Button(this);
        start.setText("저장하고 브리지 시작");
        start.setOnClickListener(ignored -> saveAndStart());
        content.addView(start);

        Button stop = new Button(this);
        stop.setText("브리지 중지");
        stop.setOnClickListener(
                ignored -> {
                    configStore.setEnabled(false);
                    BridgeService.stop(this);
                    updateStatus();
                    Toast.makeText(this, "브리지를 중지했습니다.", Toast.LENGTH_SHORT).show();
                });
        content.addView(stop);

        Button battery = new Button(this);
        battery.setText("배터리 최적화 설정 열기");
        battery.setOnClickListener(
                ignored ->
                        startActivity(
                                new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)));
        content.addView(battery);

        TextView warning = new TextView(this);
        warning.setText(
                "\n운영 전 필수: SMS 수신·발신 권한 허용, 알림 허용, 배터리 사용 '제한 없음', "
                        + "Slack 앱을 채널에 초대. 자동회신은 실통 QA 후에만 켜세요.");
        warning.setTextColor(Color.rgb(150, 70, 20));
        content.addView(warning);
        return scroll;
    }

    private void loadSettings() {
        try {
            ConfigStore.Snapshot value = configStore.read();
            botToken.setText(value.botToken);
            appToken.setText(value.appToken);
            commandPrefix.setText(value.commandPrefix);
            autoReplyEnabled.setChecked(value.autoReplyEnabled);
            autoReplyText.setText(value.autoReplyText);
            cooldownMinutes.setText(Integer.toString(value.cooldownMinutes));
            maxAutoPerHour.setText(Integer.toString(value.maxAutoRepliesPerHour));
            maxTotalPerHour.setText(Integer.toString(value.maxTotalSmsPerHour));
            blockedNumbers.setText(value.blockedNumbers);
            allowedSlackUsers.setText(value.allowedSlackUsers);
        } catch (IllegalStateException error) {
            Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void saveAndStart() {
        int cooldown = positiveInt(cooldownMinutes, "대기시간");
        int autoLimit = positiveInt(maxAutoPerHour, "자동회신 한도");
        int totalLimit = positiveInt(maxTotalPerHour, "전체 SMS 한도");
        if (cooldown < 0 || autoLimit < 0 || totalLimit < 0) {
            return;
        }
        if (!botToken.getText().toString().trim().startsWith("xoxb-")) {
            showError(botToken, "xoxb- 로 시작하는 봇 토큰이 필요합니다.");
            return;
        }
        if (!appToken.getText().toString().trim().startsWith("xapp-")) {
            showError(appToken, "xapp- 로 시작하는 Socket Mode 앱 토큰이 필요합니다.");
            return;
        }
        if (autoLimit > totalLimit) {
            showError(maxTotalPerHour, "전체 SMS 한도는 자동회신 한도 이상이어야 합니다.");
            return;
        }
        if (autoReplyEnabled.isChecked()
                && autoReplyText.getText().toString().trim().isEmpty()) {
            showError(autoReplyText, "일반 질문용 안전 회신 문구를 입력하세요.");
            return;
        }

        ConfigStore.Snapshot value =
                new ConfigStore.Snapshot(
                        botToken.getText().toString(),
                        appToken.getText().toString(),
                        ConfigStore.REQUIRED_CHANNEL_ID,
                        commandPrefix.getText().toString(),
                        autoReplyEnabled.isChecked(),
                        autoReplyText.getText().toString(),
                        cooldown,
                        autoLimit,
                        totalLimit,
                        blockedNumbers.getText().toString(),
                        allowedSlackUsers.getText().toString(),
                        true);
        try {
            configStore.save(value);
        } catch (IllegalStateException error) {
            Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }

        if (hasRequiredPermissions()) {
            BridgeService.reload(this);
            Toast.makeText(this, "브리지를 시작했습니다.", Toast.LENGTH_SHORT).show();
        } else {
            requestPermissions(requiredPermissions(), PERMISSION_REQUEST);
        }
        updateStatus();
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST && hasRequiredPermissions()) {
            BridgeService.reload(this);
            Toast.makeText(this, "권한 허용 완료 · 브리지 시작", Toast.LENGTH_SHORT).show();
        } else if (requestCode == PERMISSION_REQUEST) {
            configStore.setEnabled(false);
            Toast.makeText(
                            this,
                            "SMS 수신·발신 권한 없이는 브리지를 실행할 수 없습니다.",
                            Toast.LENGTH_LONG)
                    .show();
        }
        updateStatus();
    }

    private boolean hasRequiredPermissions() {
        return checkSelfPermission(Manifest.permission.RECEIVE_SMS)
                        == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.SEND_SMS)
                        == PackageManager.PERMISSION_GRANTED;
    }

    private String[] requiredPermissions() {
        List<String> permissions = new ArrayList<>();
        permissions.add(Manifest.permission.RECEIVE_SMS);
        permissions.add(Manifest.permission.SEND_SMS);
        if (Build.VERSION.SDK_INT >= 33) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        return permissions.toArray(new String[0]);
    }

    private void updateStatus() {
        boolean enabled = configStore != null && configStore.isEnabled();
        boolean permissions = hasRequiredPermissions();
        status.setText(
                "상태: "
                        + (enabled ? "실행 설정됨" : "중지됨")
                        + " · SMS 권한 "
                        + (permissions ? "허용" : "필요"));
    }

    private EditText addTextField(
            LinearLayout parent, String label, boolean secret, boolean multiline) {
        addLabel(parent, label);
        EditText field = new EditText(this);
        field.setTextSize(15);
        field.setSingleLine(!multiline);
        field.setMinLines(multiline ? 3 : 1);
        field.setInputType(
                secret
                        ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                        : InputType.TYPE_CLASS_TEXT
                                | (multiline ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
        if (secret) {
            field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        }
        parent.addView(field);
        return field;
    }

    private EditText addNumberField(LinearLayout parent, String label) {
        addLabel(parent, label);
        EditText field = new EditText(this);
        field.setInputType(InputType.TYPE_CLASS_NUMBER);
        field.setSingleLine(true);
        parent.addView(field);
        return field;
    }

    private void addLabel(LinearLayout parent, String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(14);
        label.setTextColor(Color.DKGRAY);
        label.setPadding(0, dp(14), 0, dp(2));
        parent.addView(label);
    }

    private int positiveInt(EditText field, String name) {
        try {
            int value = Integer.parseInt(field.getText().toString());
            if (value <= 0) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException error) {
            showError(field, name + "은 1 이상의 숫자여야 합니다.");
            return -1;
        }
    }

    private void showError(EditText field, String message) {
        field.setError(message);
        field.requestFocus();
    }

    private int dp(int value) {
        return Math.round(
                value * getResources().getDisplayMetrics().density);
    }
}
