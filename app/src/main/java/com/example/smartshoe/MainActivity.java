package com.example.smartshoe;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.Intent;     // 👈 IMPORTANTE
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// 👇 Esto le dice al analizador: "yo me encargo de los permisos en tiempo de ejecución"
@SuppressLint("MissingPermission")
public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_PERMISSIONS = 1001;

    private Button btnOpenProfile;
    private Button btnScan;
    private Button buttonPracticeMode;
    private TextView tvStatus;
    private ListView lvDevices;

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bluetoothLeScanner;
    private BluetoothGatt bluetoothGatt;

    // Lista de texto que se muestra
    private final List<String> deviceDisplayList = new ArrayList<>();
    private ArrayAdapter<String> devicesAdapter;

    // Map: línea de texto -> dirección MAC real
    private final Map<String, String> displayToAddressMap = new HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);   // <- usa tu activity_main.xml

        // 1) Referencias a la UI
        btnOpenProfile = findViewById(R.id.btnOpenProfile);
        btnScan = findViewById(R.id.btnScan);
        buttonPracticeMode = findViewById(R.id.buttonPracticeMode);
        tvStatus = findViewById(R.id.tvStatus);
        lvDevices = findViewById(R.id.lvDevices);

        // 2) Botón para abrir la pantalla de Perfil
        btnOpenProfile.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, ProfileActivity.class);
            startActivity(intent);
        });
        buttonPracticeMode.setOnClickListener(v -> {
            // para probar si llega aquí:
            // Toast.makeText(MainActivity.this, "Click en Modo Práctica", Toast.LENGTH_SHORT).show();

            Intent intent = new Intent(MainActivity.this, PracticeActivity.class);
            startActivity(intent);
        });
        // 3) Inicializar Bluetooth
        BluetoothManager bluetoothManager =
                (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager != null) {
            bluetoothAdapter = bluetoothManager.getAdapter();
        }

        if (bluetoothAdapter == null) {
            tvStatus.setText("Este dispositivo no soporta Bluetooth.");
            btnScan.setEnabled(false);
            return;
        }

        bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();

        // 4) Adapter para la lista
        devicesAdapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_list_item_1,
                deviceDisplayList
        );
        lvDevices.setAdapter(devicesAdapter);

        // 5) Click en un dispositivo de la lista -> conectarse
        lvDevices.setOnItemClickListener((parent, view, position, id) -> {
            String display = deviceDisplayList.get(position);
            String address = displayToAddressMap.get(display);
            if (address != null) {
                connectToDevice(address);
            }
        });

        // 6) Botón de escaneo
        btnScan.setOnClickListener(view -> {
            if (!hasBlePermissions()) {
                requestBlePermissions();
            } else {
                startScan();
            }
        });
    }

    // ===== PERMISOS =====

    private String[] getRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION
            };
        } else {
            return new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION
            };
        }
    }

    private boolean hasBlePermissions() {
        String[] perms = getRequiredPermissions();
        for (String perm : perms) {
            if (ContextCompat.checkSelfPermission(this, perm)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private void requestBlePermissions() {
        ActivityCompat.requestPermissions(
                this,
                getRequiredPermissions(),
                REQUEST_PERMISSIONS
        );
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            @NonNull String[] permissions,
            @NonNull int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_PERMISSIONS) {
            boolean allGranted = true;
            if (grantResults.length == 0) {
                allGranted = false;
            } else {
                for (int result : grantResults) {
                    if (result != PackageManager.PERMISSION_GRANTED) {
                        allGranted = false;
                        break;
                    }
                }
            }

            if (allGranted) {
                startScan();
            } else {
                tvStatus.setText("Permisos denegados. No puedo escanear.");
            }
        }
    }

    // ===== ESCANEO =====

    private void startScan() {
        if (!hasBlePermissions()) {
            tvStatus.setText("No tengo permisos BLE. Vuelve a intentarlo.");
            requestBlePermissions();
            return;
        }

        if (!bluetoothAdapter.isEnabled()) {
            tvStatus.setText("Activa Bluetooth en el teléfono y vuelve a intentarlo.");
            return;
        }

        tvStatus.setText("Escaneando dispositivos BLE...");
        deviceDisplayList.clear();
        displayToAddressMap.clear();
        devicesAdapter.notifyDataSetChanged();

        if (bluetoothLeScanner == null) {
            bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
        }

        if (bluetoothLeScanner == null) {
            tvStatus.setText("No se pudo obtener el escáner BLE.");
            return;
        }

        try {
            bluetoothLeScanner.startScan(scanCallback);
        } catch (SecurityException e) {
            tvStatus.setText("Error de permisos al iniciar el escaneo BLE.");
            return;
        }

        // Detener el escaneo después de 20 segundos
        tvStatus.postDelayed(this::stopScan, 20_000);
    }

    private void stopScan() {
        if (bluetoothLeScanner != null) {
            try {
                bluetoothLeScanner.stopScan(scanCallback);
            } catch (SecurityException e) {
                tvStatus.append("\nError de permisos al detener el escaneo.");
            }
        }
        tvStatus.append("\nEscaneo terminado.");
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            super.onScanResult(callbackType, result);

            if (result == null || result.getDevice() == null) return;

            String name = result.getDevice().getName();
            String address = result.getDevice().getAddress();

            if (name == null || name.isEmpty()) {
                name = "Sin nombre";
            }
            if (address == null) {
                return;
            }

            String display = name + " (" + address + ")";

            if (!displayToAddressMap.containsKey(display)) {
                displayToAddressMap.put(display, address);
                deviceDisplayList.add(display);
                devicesAdapter.notifyDataSetChanged();
            }
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            super.onBatchScanResults(results);
            if (results == null) return;
            for (ScanResult result : results) {
                onScanResult(0, result);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            super.onScanFailed(errorCode);
            tvStatus.append("\nError de escaneo: " + errorCode);
        }
    };

    // ===== CONEXIÓN GATT =====

    private void connectToDevice(String address) {
        if (!hasBlePermissions()) {
            tvStatus.setText("No tengo permisos BLE para conectar.");
            requestBlePermissions();
            return;
        }

        BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
        if (device == null) {
            tvStatus.setText("No se pudo obtener el dispositivo para conectar.");
            return;
        }

        tvStatus.setText("Conectando a " + address + "...");

        if (bluetoothGatt != null) {
            bluetoothGatt.close();
            bluetoothGatt = null;
        }

        try {
            bluetoothGatt = device.connectGatt(this, false, gattCallback);
        } catch (SecurityException e) {
            tvStatus.setText("Error de permisos al conectar con el dispositivo.");
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(
                BluetoothGatt gatt,
                int status,
                int newState
        ) {
            super.onConnectionStateChange(gatt, status, newState);

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                runOnUiThread(() -> tvStatus.setText("Conectado. Descubriendo servicios..."));
                gatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                runOnUiThread(() -> tvStatus.setText("Desconectado del dispositivo."));
            }
        }

        @Override
        public void onServicesDiscovered(
                BluetoothGatt gatt,
                int status
        ) {
            super.onServicesDiscovered(gatt, status);

            runOnUiThread(() -> tvStatus.setText(
                    "Servicios descubiertos. Listo para enviar comandos (cuando definamos UUIDs)."
            ));
            // Aquí después buscaremos el service/characteristic para el zapato.
        }
    };

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopScan();
        if (bluetoothGatt != null) {
            bluetoothGatt.close();
            bluetoothGatt = null;
        }
    }
}
