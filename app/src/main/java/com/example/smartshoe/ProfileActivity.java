package com.example.smartshoe;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class ProfileActivity extends AppCompatActivity {

    private TextView textVibrationLabel;

    private EditText editTextName;
    private RadioGroup radioGroupRole;
    private RadioButton radioWoman, radioMan;
    private TextView textStartFootInfo;
    private Button buttonSelectRightShoe, buttonSelectLeftShoe;
    private TextView textRightShoeInfo, textLeftShoeInfo;
    private SeekBar seekBarVibration;
    private Button buttonTestRightVibration, buttonTestLeftVibration, buttonSaveProfile;

    private ShoeDevice rightShoe;
    private ShoeDevice leftShoe;
    private int vibrationIntensity = 50; // valor por defecto

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profile);

        initViews();
        setupListeners();
        loadProfileFromPreferences();
    }

    private void initViews() {
        editTextName = findViewById(R.id.editTextName);
        radioGroupRole = findViewById(R.id.radioGroupRole);
        radioWoman = findViewById(R.id.radioWoman);
        radioMan = findViewById(R.id.radioMan);
        textStartFootInfo = findViewById(R.id.textStartFootInfo);

        buttonSelectRightShoe = findViewById(R.id.buttonSelectRightShoe);
        buttonSelectLeftShoe = findViewById(R.id.buttonSelectLeftShoe);
        textRightShoeInfo = findViewById(R.id.textRightShoeInfo);
        textLeftShoeInfo = findViewById(R.id.textLeftShoeInfo);

        seekBarVibration = findViewById(R.id.seekBarVibration);
        buttonTestRightVibration = findViewById(R.id.buttonTestRightVibration);
        buttonTestLeftVibration = findViewById(R.id.buttonTestLeftVibration);
        buttonSaveProfile = findViewById(R.id.buttonSaveProfile);
    }

    private void setupListeners() {
        radioGroupRole.setOnCheckedChangeListener((group, checkedId) -> {
            UserProfile.Role role;
            UserProfile.StartFoot startFoot;

            if (checkedId == R.id.radioWoman) {
                role = UserProfile.Role.WOMAN;
                startFoot = UserProfile.StartFoot.RIGHT;
            } else {
                role = UserProfile.Role.MAN;
                startFoot = UserProfile.StartFoot.LEFT;
            }

            textStartFootInfo.setText("Pie de inicio: " +
                    (startFoot == UserProfile.StartFoot.LEFT ? "Izquierdo" : "Derecho"));
        });
        textVibrationLabel = findViewById(R.id.textVibrationLabel);

        seekBarVibration.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                vibrationIntensity = progress;
                textVibrationLabel.setText("Intensidad: " + progress + "%");
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        buttonSelectRightShoe.setOnClickListener(v -> {
            // TODO: aquí más adelante abriremos tu pantalla de escaneo BLE
            Toast.makeText(this, "Aquí iría la selección del zapato derecho (BLE)", Toast.LENGTH_SHORT).show();
        });

        buttonSelectLeftShoe.setOnClickListener(v -> {
            // TODO: selección del zapato izquierdo
            Toast.makeText(this, "Aquí iría la selección del zapato izquierdo (BLE)", Toast.LENGTH_SHORT).show();
        });

        buttonTestRightVibration.setOnClickListener(v -> {
            // TODO: enviar comando BLE al rightShoe con intensidad vibrationIntensity
            Toast.makeText(this, "Test vibración zapato derecho (todavía sin BLE)", Toast.LENGTH_SHORT).show();
        });

        buttonTestLeftVibration.setOnClickListener(v -> {
            // TODO: enviar comando BLE al leftShoe con intensidad vibrationIntensity
            Toast.makeText(this, "Test vibración zapato izquierdo (todavía sin BLE)", Toast.LENGTH_SHORT).show();
        });

        buttonSaveProfile.setOnClickListener(v -> saveProfileToPreferences());
    }

    private void saveProfileToPreferences() {
        String name = editTextName.getText().toString().trim();

        UserProfile.Role role =
                radioWoman.isChecked() ? UserProfile.Role.WOMAN : UserProfile.Role.MAN;

        UserProfile.StartFoot startFoot =
                role == UserProfile.Role.WOMAN ?
                        UserProfile.StartFoot.RIGHT : UserProfile.StartFoot.LEFT;

        UserProfile profile = new UserProfile(
                name,
                role,
                startFoot,
                leftShoe,
                rightShoe,
                vibrationIntensity
        );

        SharedPreferences prefs = getSharedPreferences("user_profile", MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();

        editor.putString("name", profile.getName());
        editor.putString("role", profile.getRole().name());
        editor.putString("startFoot", profile.getStartFoot().name());
        editor.putInt("vibrationIntensity", profile.getVibrationIntensity());

        if (rightShoe != null) {
            editor.putString("rightShoeId", rightShoe.getId());
            editor.putString("rightShoeName", rightShoe.getName());
        }
        if (leftShoe != null) {
            editor.putString("leftShoeId", leftShoe.getId());
            editor.putString("leftShoeName", leftShoe.getName());
        }

        editor.apply();

        Toast.makeText(this, "Perfil guardado", Toast.LENGTH_SHORT).show();
    }

    private void loadProfileFromPreferences() {
        SharedPreferences prefs = getSharedPreferences("user_profile", MODE_PRIVATE);

        String name = prefs.getString("name", "");
        String roleStr = prefs.getString("role", null);
        String startFootStr = prefs.getString("startFoot", null);
        int vib = prefs.getInt("vibrationIntensity", 50);

        editTextName.setText(name);
        vibrationIntensity = vib;
        seekBarVibration.setProgress(vib);

        if (roleStr != null) {
            UserProfile.Role role = UserProfile.Role.valueOf(roleStr);
            if (role == UserProfile.Role.WOMAN) {
                radioWoman.setChecked(true);
            } else {
                radioMan.setChecked(true);
            }
        }

        if (startFootStr != null) {
            UserProfile.StartFoot startFoot = UserProfile.StartFoot.valueOf(startFootStr);
            textStartFootInfo.setText("Pie de inicio: " +
                    (startFoot == UserProfile.StartFoot.LEFT ? "Izquierdo" : "Derecho"));
        }

        String rightId = prefs.getString("rightShoeId", null);
        String rightName = prefs.getString("rightShoeName", null);
        if (rightId != null) {
            rightShoe = new ShoeDevice(rightId, rightName);
            textRightShoeInfo.setText("Derecho: " + rightName + " (" + rightId + ")");
        }

        String leftId = prefs.getString("leftShoeId", null);
        String leftName = prefs.getString("leftShoeName", null);
        if (leftId != null) {
            leftShoe = new ShoeDevice(leftId, leftName);
            textLeftShoeInfo.setText("Izquierdo: " + leftName + " (" + leftId + ")");
        }
    }
}