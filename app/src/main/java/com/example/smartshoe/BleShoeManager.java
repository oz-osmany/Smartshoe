package com.example.smartshoe;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
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
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@SuppressLint("MissingPermission")
public class BleShoeManager {

    public interface Listener {
        void onShoeBReady(ShoeDevice shoeDevice);
        void onStatus(String message);
        void onDeviceFound(BluetoothDevice device, String displayText);
        void onShoeAReady(ShoeDevice shoeDevice);
        void onError(String message);
    }

    private static final String PREFS_NAME = "user_profile";
    private static final String KEY_LEFT_SHOE_ID = "leftShoeId";
    private static final String KEY_RIGHT_SHOE_ID = "rightShoeId";
    private static final String KEY_RIGHT_SHOE_NAME = "rightShoeName";
    private static final String KEY_LEFT_SHOE_NAME = "leftShoeName";

    private static final UUID SHOE_SERVICE_UUID =
            UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID SHOE_CHARACTERISTIC_UUID =
            UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");

    private final Context appContext;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bluetoothLeScanner;

    private BluetoothGatt gattA;
    private BluetoothGattCharacteristic charA;
    private boolean isConnectingA = false;
    private BluetoothGatt gattB;
    private BluetoothGattCharacteristic charB;
    private boolean isConnectingB = false;

    private BluetoothDevice pendingDeviceToPairA;
    private final Set<String> discoveredAddresses = new HashSet<>();

    public BleShoeManager(Context context, Listener listener) {
        this.appContext = context.getApplicationContext();
        this.listener = listener;

        BluetoothManager bluetoothManager =
                (BluetoothManager) appContext.getSystemService(Context.BLUETOOTH_SERVICE);

        if (bluetoothManager != null) {
            bluetoothAdapter = bluetoothManager.getAdapter();
        }

        if (bluetoothAdapter != null) {
            bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
        }
    }

    public boolean isBluetoothAvailable() {
        return bluetoothAdapter != null;
    }

    public boolean isBluetoothEnabled() {
        return bluetoothAdapter != null && bluetoothAdapter.isEnabled();
    }

    public boolean hasScanPermission(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    public boolean hasConnectPermission(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    public void requestBlePermissions(Activity activity, int requestCode) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(
                    activity,
                    new String[]{
                            Manifest.permission.BLUETOOTH_SCAN,
                            Manifest.permission.BLUETOOTH_CONNECT,
                            Manifest.permission.ACCESS_FINE_LOCATION
                    },
                    requestCode
            );
        } else {
            ActivityCompat.requestPermissions(
                    activity,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    requestCode
            );
        }
    }

    public void startScan(Context context) {
        if (!isBluetoothAvailable()) {
            postError("Este dispositivo no soporta Bluetooth.");
            return;
        }

        if (!isBluetoothEnabled()) {
            postError("Activa Bluetooth en el teléfono.");
            return;
        }

        if (!hasScanPermission(context)) {
            postError("Faltan permisos BLE para escanear.");
            return;
        }

        if (bluetoothLeScanner == null) {
            bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
        }

        if (bluetoothLeScanner == null) {
            postError("No se pudo obtener el escáner BLE.");
            return;
        }

        discoveredAddresses.clear();
        postStatus("Buscando dispositivos BLE...");

        try {
            bluetoothLeScanner.startScan(scanCallback);
        } catch (SecurityException e) {
            postError("No pude iniciar el escaneo BLE por permisos.");
        }
    }

    public void stopScan() {
        if (bluetoothLeScanner == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED) {
                postError("No tengo permiso para detener el escaneo BLE.");
                return;
            }
        }

        try {
            bluetoothLeScanner.stopScan(scanCallback);
            postStatus("Escaneo detenido.");
        } catch (SecurityException e) {
            postError("No pude detener el escaneo BLE por permisos.");
        } catch (Exception e) {
            postError("Error deteniendo escaneo: " + e.getMessage());
        }
    }

    public ShoeDevice getSavedShoeA() {
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String id = prefs.getString(KEY_LEFT_SHOE_ID, null);
        String name = prefs.getString(KEY_LEFT_SHOE_NAME, null);

        if (id == null || id.trim().isEmpty()) {
            return null;
        }

        return new ShoeDevice(id, name != null ? name : "Zapato A");
    }
    public ShoeDevice getSavedShoeB() {

        SharedPreferences prefs =
                appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        String id = prefs.getString(KEY_RIGHT_SHOE_ID, null);
        String name = prefs.getString(KEY_RIGHT_SHOE_NAME, null);

        if (id == null || id.trim().isEmpty()) {
            return null;
        }

        return new ShoeDevice(
                id,
                name != null ? name : "Zapato B"
        );
    }
    public void connectAndSaveAsShoeA(Context context, BluetoothDevice device) {
        if (device == null) {
            postError("Dispositivo inválido.");
            return;
        }

        if (!hasConnectPermission(context)) {
            postError("Falta permiso BLUETOOTH_CONNECT.");
            return;
        }

        pendingDeviceToPairA = device;
        connectGattA(context, device, true);
    }

    public void connectSavedShoeA(Context context) {
        ShoeDevice saved = getSavedShoeA();
        if (saved == null) {
            postStatus("No hay zapato A guardado.");
            return;
        }

        if (!isBluetoothAvailable() || !isBluetoothEnabled()) {
            postError("Bluetooth no disponible o apagado.");
            return;
        }

        if (!hasConnectPermission(context)) {
            postError("Falta permiso BLUETOOTH_CONNECT.");
            return;
        }

        try {
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(saved.getId());
            connectGattA(context, device, false);
        } catch (IllegalArgumentException e) {
            postError("La dirección BLE guardada del zapato A no es válida.");
        }
    }

    private void connectGattA(Context context, BluetoothDevice device, boolean saveOnReady) {
        if (device == null) {
            postError("No se pudo recuperar el dispositivo BLE.");
            return;
        }

        if (isConnectingA) return;
        if (gattA != null && charA != null) {
            postStatus("Zapato A ya está listo.");
            return;
        }

        safeCloseGattA(context);

        isConnectingA = true;
        postStatus("Conectando con zapato A...");

        try {
            gattA = device.connectGatt(context, false, new BluetoothGattCallback() {
                @Override
                public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        isConnectingA = false;
                        safeCloseGattA(context);
                        postError("Falló la conexión GATT del zapato A. status=" + status);
                        return;
                    }

                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        isConnectingA = false;
                        postStatus("Zapato A conectado. Descubriendo servicios...");

                        if (!hasConnectPermission(context)) {
                            postError("Permiso BLUETOOTH_CONNECT faltante al descubrir servicios.");
                            return;
                        }

                        try {
                            gatt.discoverServices();
                        } catch (SecurityException e) {
                            postError("No se pudieron descubrir servicios por permisos.");
                        }

                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        isConnectingA = false;
                        charA = null;
                        postStatus("Zapato A desconectado.");
                    }
                }

                @Override
                public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        postError("Error al descubrir servicios del zapato A. status=" + status);
                        return;
                    }

                    BluetoothGattService service = gatt.getService(SHOE_SERVICE_UUID);
                    if (service == null) {
                        postError("El dispositivo no tiene el servicio BLE esperado.");
                        return;
                    }

                    BluetoothGattCharacteristic characteristic =
                            service.getCharacteristic(SHOE_CHARACTERISTIC_UUID);

                    if (characteristic == null) {
                        postError("El dispositivo no tiene la característica de control.");
                        return;
                    }

                    characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                    charA = characteristic;

                    BluetoothDevice readyDevice = gatt.getDevice();
                    String name = safeDeviceName(readyDevice);
                    String address = readyDevice != null ? readyDevice.getAddress() : null;

                    if (saveOnReady && address != null) {
                        saveShoeA(address, name);
                    }

                    postStatus("Zapato A listo para recibir comandos.");

                    if (address != null) {
                        postShoeAReady(new ShoeDevice(address, name));
                    }
                }
            });
        } catch (SecurityException e) {
            isConnectingA = false;
            postError("No se pudo abrir GATT por permisos.");
        }
    }
    private void connectGattB(
            Context context,
            BluetoothDevice device,
            boolean saveOnReady
    ) {

        if (device == null) {
            postError("No se pudo recuperar el dispositivo B.");
            return;
        }

        if (isConnectingB) return;

        if (gattB != null && charB != null) {
            postStatus("Zapato B ya está listo.");
            return;
        }

        safeCloseGattB(context);

        isConnectingB = true;

        postStatus("Conectando con zapato B...");

        try {

            gattB = device.connectGatt(
                    context,
                    false,
                    new BluetoothGattCallback() {

                        @Override
                        public void onConnectionStateChange(
                                BluetoothGatt gatt,
                                int status,
                                int newState
                        ) {

                            if (status != BluetoothGatt.GATT_SUCCESS) {

                                isConnectingB = false;
                                safeCloseGattB(context);

                                postError(
                                        "Falló conexión GATT zapato B. status=" + status
                                );

                                return;
                            }

                            if (newState == BluetoothProfile.STATE_CONNECTED) {

                                isConnectingB = false;

                                postStatus(
                                        "Zapato B conectado. Descubriendo servicios..."
                                );

                                if (!hasConnectPermission(context)) {
                                    postError("Falta permiso BLUETOOTH_CONNECT.");
                                    return;
                                }

                                try {
                                    gatt.discoverServices();
                                } catch (SecurityException e) {
                                    postError(
                                            "No se pudieron descubrir servicios B."
                                    );
                                }

                            } else if (
                                    newState == BluetoothProfile.STATE_DISCONNECTED
                            ) {

                                isConnectingB = false;
                                charB = null;

                                postStatus("Zapato B desconectado.");
                            }
                        }

                        @Override
                        public void onServicesDiscovered(
                                BluetoothGatt gatt,
                                int status
                        ) {

                            if (status != BluetoothGatt.GATT_SUCCESS) {

                                postError(
                                        "Error descubriendo servicios B. status=" + status
                                );

                                return;
                            }

                            BluetoothGattService service =
                                    gatt.getService(SHOE_SERVICE_UUID);

                            if (service == null) {
                                postError(
                                        "Zapato B sin servicio BLE esperado."
                                );
                                return;
                            }

                            BluetoothGattCharacteristic characteristic =
                                    service.getCharacteristic(
                                            SHOE_CHARACTERISTIC_UUID
                                    );

                            if (characteristic == null) {
                                postError(
                                        "Zapato B sin característica BLE."
                                );
                                return;
                            }

                            gattB = gatt;
                            charB = characteristic;

                            charB.setWriteType(
                                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                            );

                            BluetoothDevice readyDevice = gatt.getDevice();

                            String name = safeDeviceName(readyDevice);

                            String address =
                                    readyDevice != null
                                            ? readyDevice.getAddress()
                                            : null;

                            if (saveOnReady && address != null) {
                                saveShoeB(address, name);
                            }

                            postStatus(
                                    "Zapato B listo para recibir comandos."
                            );
                            if (address != null) {
                                postShoeBReady(new ShoeDevice(address, name));
                            }
                        }
                    }
            );

        } catch (SecurityException e) {

            isConnectingB = false;

            postError("No se pudo abrir GATT B.");
        }
    }

    public boolean isShoeAReady() {
        return gattA != null && charA != null;
    }

    public boolean sendToShoeA(Context context, String payload) {
        if (payload == null || payload.trim().isEmpty()) {
            return false;
        }

        if (!hasConnectPermission(context)) {
            postError("Falta permiso BLUETOOTH_CONNECT para enviar comandos.");
            return false;
        }

        if (gattA == null) {
            postStatus("Zapato A no tiene conexión GATT.");
            return false;
        }

        if (charA == null) {
            postStatus("Zapato A no tiene característica lista.");
            return false;
        }

        try {
            String finalPayload = payload.endsWith("\n") ? payload : payload + "\n";

            charA.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            charA.setValue(finalPayload.getBytes());

            return gattA.writeCharacteristic(charA);

        } catch (SecurityException e) {
            postError("No se pudo escribir al zapato A por permisos.");
            return false;
        } catch (Exception e) {
            postError("Error enviando comando al zapato A: " + e.getMessage());
            return false;
        }
    }
    public boolean sendToShoeB(Context context, String payload) {

        if (payload == null || payload.trim().isEmpty()) {
            return false;
        }

        if (!hasConnectPermission(context)) {
            postError("Falta permiso BLUETOOTH_CONNECT.");
            return false;
        }

        if (gattB == null) {
            postStatus("Zapato B sin conexión.");
            return false;
        }

        if (charB == null) {
            postStatus("Zapato B no listo.");
            return false;
        }

        try {

            String finalPayload =
                    payload.endsWith("\n")
                            ? payload
                            : payload + "\n";

            charB.setWriteType(
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            );

            charB.setValue(finalPayload.getBytes());

            return gattB.writeCharacteristic(charB);

        } catch (SecurityException e) {

            postError("Error escribiendo en zapato B.");
            return false;

        } catch (Exception e) {

            postError(
                    "Error enviando comando a zapato B: " + e.getMessage()
            );

            return false;
        }
    }
    public void clearSavedShoeA() {
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit()
                .remove(KEY_LEFT_SHOE_ID)
                .remove(KEY_LEFT_SHOE_NAME)
                .apply();
    }

    public void close(Context context) {
        stopScan();
        safeCloseGattA(context);
        safeCloseGattB(context);
    }

    private void safeCloseGattA(Context context) {
        charA = null;
        isConnectingA = false;

        if (gattA != null) {
            if (!hasConnectPermission(context)) {
                gattA = null;
                return;
            }

            try {
                gattA.close();
            } catch (SecurityException ignored) {
            } catch (Exception ignored) {
            }

            gattA = null;
        }
    }
    private void safeCloseGattB(Context context) {

        charB = null;
        isConnectingB = false;

        if (gattB != null) {

            if (!hasConnectPermission(context)) {
                gattB = null;
                return;
            }

            try {
                gattB.close();
            } catch (Exception ignored) {
            }

            gattB = null;
        }
    }
    private void saveShoeA(String address, String name) {
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit()
                .putString(KEY_LEFT_SHOE_ID, address)
                .putString(KEY_LEFT_SHOE_NAME, name)
                .apply();
    }
    private void saveShoeB(String address, String name) {

        SharedPreferences prefs =
                appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        prefs.edit()
                .putString(KEY_RIGHT_SHOE_ID, address)
                .putString(KEY_RIGHT_SHOE_NAME, name)
                .apply();
    }
    public void connectAndSaveAsShoeB(Context context, BluetoothDevice device) {

        if (device == null) {
            postError("Dispositivo B inválido.");
            return;
        }

        if (!hasConnectPermission(context)) {
            postError("Falta permiso BLUETOOTH_CONNECT.");
            return;
        }

        connectGattB(context, device, true);
    }
    public void connectSavedShoeB(Context context) {

        ShoeDevice saved = getSavedShoeB();

        if (saved == null) {
            postStatus("No hay zapato B guardado.");
            return;
        }

        if (!isBluetoothAvailable() || !isBluetoothEnabled()) {
            postError("Bluetooth no disponible.");
            return;
        }

        if (!hasConnectPermission(context)) {
            postError("Falta permiso BLUETOOTH_CONNECT.");
            return;
        }

        try {

            BluetoothDevice device =
                    bluetoothAdapter.getRemoteDevice(saved.getId());

            connectGattB(context, device, false);

        } catch (IllegalArgumentException e) {

            postError("Dirección BLE inválida para zapato B.");
        }
    }

    private String safeDeviceName(BluetoothDevice device) {
        if (device == null) return "Sin nombre";

        try {
            String name = device.getName();
            return (name == null || name.trim().isEmpty()) ? "Sin nombre" : name;
        } catch (SecurityException e) {
            return "Sin nombre";
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            if (result == null || result.getDevice() == null) return;

            BluetoothDevice device = result.getDevice();
            String address = device.getAddress();
            if (address == null || address.trim().isEmpty()) return;
            if (discoveredAddresses.contains(address)) return;

            discoveredAddresses.add(address);

            String displayText = safeDeviceName(device) + " (" + address + ")";
            postDeviceFound(device, displayText);
        }
    };

    private void postStatus(String message) {
        if (listener == null) return;
        mainHandler.post(() -> listener.onStatus(message));
    }

    private void postError(String message) {
        if (listener == null) return;
        mainHandler.post(() -> listener.onError(message));
    }

    private void postDeviceFound(BluetoothDevice device, String displayText) {
        if (listener == null) return;
        mainHandler.post(() -> listener.onDeviceFound(device, displayText));
    }

    private void postShoeAReady(ShoeDevice shoeDevice) {
        if (listener == null) return;
        mainHandler.post(() -> listener.onShoeAReady(shoeDevice));

    }
    private void postShoeBReady(ShoeDevice shoeDevice) {
        if (listener == null) return;
        mainHandler.post(() -> listener.onShoeBReady(shoeDevice));
    }
}