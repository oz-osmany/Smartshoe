package com.example.smartshoe;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import java.util.UUID;

@SuppressLint("MissingPermission")
public class MainActivity extends AppCompatActivity {



    private static final int REQUEST_PERMISSIONS = 1001;

    // UUIDs de tu ESP32 (Nordic UART)
    private static final UUID SHOE_SERVICE_UUID =
            UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID SHOE_CHARACTERISTIC_UUID =
            UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");

    private Button btnOpenProfile;
    private Button btnScan;
    private Button btnScanB;
    private enum PairingTarget {
        A, B
    }

    private PairingTarget currentPairingTarget = PairingTarget.A;
    private Button buttonPracticeMode;
    private TextView tvStatus;
    private ListView lvDevices;

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bluetoothLeScanner;
    private BluetoothGatt bluetoothGatt;

    private final List<String> deviceDisplayList = new ArrayList<>();
    private BleShoeManager bleShoeManager;
    private final Handler scanHandler = new Handler(Looper.getMainLooper());
    private final Runnable stopScanRunnable = () -> {
        if (bleShoeManager != null) {
            bleShoeManager.stopScan();
        }
    };
    private final Map<String, BluetoothDevice> displayToDeviceMap = new HashMap<>();
    private ArrayAdapter<String> devicesAdapter;

    private BluetoothDevice pendingDeviceToPair;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnOpenProfile = findViewById(R.id.btnOpenProfile);
        btnScan = findViewById(R.id.btnScan);
        btnScanB = findViewById(R.id.btnScanB);
        buttonPracticeMode = findViewById(R.id.buttonPracticeMode);
        tvStatus = findViewById(R.id.tvStatus);
        lvDevices = findViewById(R.id.lvDevices);

        btnOpenProfile.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, ProfileActivity.class);
            startActivity(intent);
        });

        buttonPracticeMode.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, PracticeActivity.class);
            startActivity(intent);
        });

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

        devicesAdapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_list_item_1,
                deviceDisplayList
        );
        lvDevices.setAdapter(devicesAdapter);

        lvDevices.setOnItemClickListener((parent, view, position, id) -> {
            String display = deviceDisplayList.get(position);
            BluetoothDevice device = displayToDeviceMap.get(display);

            if (device == null) {
                tvStatus.setText("No pude recuperar el dispositivo seleccionado.");
                return;
            }

            scanHandler.removeCallbacks(stopScanRunnable);

            if (bleShoeManager != null) {
                bleShoeManager.stopScan();
            }

            if (currentPairingTarget == PairingTarget.A) {
                bleShoeManager.connectAndSaveAsShoeA(this, device);
            } else {
                bleShoeManager.connectAndSaveAsShoeB(this, device);
            }
        });

        btnScan.setOnClickListener(view -> {
            currentPairingTarget = PairingTarget.A;
            startBleScanForPairing();
        });

        btnScanB.setOnClickListener(view -> {
            currentPairingTarget = PairingTarget.B;
            startBleScanForPairing();
        });
        bleShoeManager = new BleShoeManager(this, new BleShoeManager.Listener() {
            @Override
            public void onStatus(String message) {
                tvStatus.setText(message);
            }

            @Override
            public void onDeviceFound(BluetoothDevice device, String displayText) {
                if (!displayToDeviceMap.containsKey(displayText)) {
                    displayToDeviceMap.put(displayText, device);
                    deviceDisplayList.add(displayText);
                    devicesAdapter.notifyDataSetChanged();
                }
            }

            @Override
            public void onShoeAReady(ShoeDevice shoeDevice) {
                tvStatus.setText("Zapato A enlazado: " + shoeDevice.getName());
            }

            @Override
            public void onShoeBReady(ShoeDevice shoeDevice) {
                tvStatus.setText("Zapato B enlazado: " + shoeDevice.getName());
            }

            @Override
            public void onError(String message) {
                tvStatus.setText(message);
            }
        });

        showSavedShoeInfo();
    }
    private void startBleScanForPairing() {
        if (!bleShoeManager.hasScanPermission(this) ||
                !bleShoeManager.hasConnectPermission(this)) {

            bleShoeManager.requestBlePermissions(this, REQUEST_PERMISSIONS);

        } else {
            deviceDisplayList.clear();
            displayToDeviceMap.clear();
            devicesAdapter.notifyDataSetChanged();

            String targetText = currentPairingTarget == PairingTarget.A
                    ? "zapato A"
                    : "zapato B";

            tvStatus.setText("Buscando " + targetText + "...");

            bleShoeManager.startScan(this);
            scanHandler.removeCallbacks(stopScanRunnable);
            scanHandler.postDelayed(stopScanRunnable, 15000);
        }
    }
    private void showSavedShoeInfo() {
        SharedPreferences prefs = getSharedPreferences("user_profile", MODE_PRIVATE);
        String leftName = prefs.getString("leftShoeName", null);
        String leftId = prefs.getString("leftShoeId", null);

        if (leftId != null) {
            tvStatus.setText("Zapato A guardado: " + leftName + " (" + leftId + ")");
        } else {
            tvStatus.setText("Aún no hay un zapato A enlazado.");
        }
    }

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
                startBleScanForPairing();
            } else {
                tvStatus.setText("Permisos denegados. No puedo escanear.");
            }
        }
    }

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

        tvStatus.setText("Buscando dispositivos BLE para enlazar zapato A...");
        deviceDisplayList.clear();
        displayToDeviceMap.clear();
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

        tvStatus.postDelayed(this::stopScan, 15000);
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

            BluetoothDevice device = result.getDevice();
            String name = device.getName();
            String address = device.getAddress();

            if (name == null || name.trim().isEmpty()) {
                name = "Sin nombre";
            }

            if (address == null || address.trim().isEmpty()) {
                return;
            }

            String display = name + " (" + address + ")";

            if (!displayToDeviceMap.containsKey(display)) {
                displayToDeviceMap.put(display, device);
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
            tvStatus.setText("Error de escaneo: " + errorCode);
        }
    };

    private void connectToDevice(BluetoothDevice device) {
        if (!hasBlePermissions()) {
            tvStatus.setText("No tengo permisos BLE para conectar.");
            requestBlePermissions();
            return;
        }

        if (device == null) {
            tvStatus.setText("Dispositivo inválido.");
            return;
        }

        String name = device.getName() != null ? device.getName() : "Sin nombre";
        String address = device.getAddress();

        tvStatus.setText("Conectando a " + name + " (" + address + ")...");

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
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            super.onConnectionStateChange(gatt, status, newState);

            if (status != BluetoothGatt.GATT_SUCCESS) {
                runOnUiThread(() ->
                        tvStatus.setText("Falló la conexión GATT. status=" + status));
                gatt.close();
                return;
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                runOnUiThread(() -> tvStatus.setText("Conectado. Descubriendo servicios..."));
                gatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                runOnUiThread(() -> tvStatus.setText("Dispositivo desconectado."));
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            super.onServicesDiscovered(gatt, status);

            if (status != BluetoothGatt.GATT_SUCCESS) {
                runOnUiThread(() ->
                        tvStatus.setText("Error descubriendo servicios. status=" + status));
                return;
            }

            BluetoothGattService service = gatt.getService(SHOE_SERVICE_UUID);
            if (service == null) {
                runOnUiThread(() ->
                        tvStatus.setText("El dispositivo no tiene el servicio del zapato."));
                return;
            }

            BluetoothGattCharacteristic characteristic =
                    service.getCharacteristic(SHOE_CHARACTERISTIC_UUID);

            if (characteristic == null) {
                runOnUiThread(() ->
                        tvStatus.setText("El dispositivo no tiene la característica de control."));
                return;
            }

            if (pendingDeviceToPair != null) {
                saveDeviceAsShoeA(pendingDeviceToPair);
                String name = pendingDeviceToPair.getName() != null
                        ? pendingDeviceToPair.getName()
                        : "Sin nombre";

                runOnUiThread(() ->
                        tvStatus.setText("Zapato A enlazado correctamente: " + name));
            } else {
                runOnUiThread(() ->
                        tvStatus.setText("Servicio encontrado, pero no había dispositivo pendiente."));
            }
        }
    };

    private void saveDeviceAsShoeA(BluetoothDevice device) {
        SharedPreferences prefs = getSharedPreferences("user_profile", MODE_PRIVATE);
        prefs.edit()
                .putString("leftShoeId", device.getAddress())
                .putString("leftShoeName", device.getName() != null ? device.getName() : "Sin nombre")
                .apply();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        scanHandler.removeCallbacks(stopScanRunnable);

        if (bleShoeManager != null) {
            bleShoeManager.stopScan();
        }
    }
}