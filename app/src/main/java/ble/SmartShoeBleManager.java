package ble;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class SmartShoeBleManager {

    private static final String TAG = "SmartShoeBleManager";

    // Nombre del dispositivo BLE (el mismo que pusimos en el ESP32)
    private static final String DEVICE_NAME = "SmartShoe";

    // UUIDs del servicio y characteristic (los mismos del ESP32)
    private static final UUID SERVICE_UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID CHARACTERISTIC_UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");

    private final Context context;
    private final BluetoothAdapter bluetoothAdapter;

    private BluetoothGatt bluetoothGatt;
    private BluetoothGattCharacteristic writeCharacteristic;
    private boolean isConnected = false;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface ConnectionListener {
        void onConnected();
        void onDisconnected();
        void onError(String message);
    }

    private ConnectionListener connectionListener;

    public SmartShoeBleManager(Context context) {
        this.context = context.getApplicationContext();
        BluetoothManager bluetoothManager =
                (BluetoothManager) this.context.getSystemService(Context.BLUETOOTH_SERVICE);
        this.bluetoothAdapter = bluetoothManager != null ? bluetoothManager.getAdapter() : null;
    }

    public void setConnectionListener(ConnectionListener listener) {
        this.connectionListener = listener;
    }

    // Llamar desde tu Activity para comenzar a buscar y conectar
    public void connect() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            if (connectionListener != null) {
                connectionListener.onError("Bluetooth apagado o no disponible");
            }
            return;
        }

        // Scan rápido buscando por nombre
        bluetoothAdapter.startLeScan(leScanCallback);

        // Detener el scan después de unos segundos
        mainHandler.postDelayed(() -> bluetoothAdapter.stopLeScan(leScanCallback), 8000);
    }

    // Desconectar y liberar recursos
    public void disconnect() {
        if (bluetoothGatt != null) {
            bluetoothGatt.disconnect();
            bluetoothGatt.close();
            bluetoothGatt = null;
        }
        isConnected = false;
    }

    // Enviar comando al ESP32 (ej: "A1", "B3", "STOP")
    public void sendCommand(String command) {
        if (!isConnected || writeCharacteristic == null || bluetoothGatt == null) {
            Log.w(TAG, "No conectado o characteristic no disponible. Cmd: " + command);
            return;
        }

        byte[] data = command.getBytes(StandardCharsets.UTF_8);
        writeCharacteristic.setValue(data);
        writeCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        boolean success = bluetoothGatt.writeCharacteristic(writeCharacteristic);

        Log.d(TAG, "Enviando comando: " + command + " -> " + success);
    }

    // ---- Callbacks BLE internos ----

    private final BluetoothAdapter.LeScanCallback leScanCallback = new BluetoothAdapter.LeScanCallback() {
        @Override
        public void onLeScan(BluetoothDevice device, int rssi, byte[] scanRecord) {
            if (device == null || device.getName() == null) return;

            Log.d(TAG, "Encontrado: " + device.getName() + " (" + device.getAddress() + ")");

            if (DEVICE_NAME.equals(device.getName())) {
                Log.d(TAG, "SmartShoe encontrado, conectando...");
                bluetoothAdapter.stopLeScan(leScanCallback);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
                } else {
                    bluetoothGatt = device.connectGatt(context, false, gattCallback);
                }
            }
        }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            Log.d(TAG, "onConnectionStateChange: status=" + status + " newState=" + newState);

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                isConnected = true;
                Log.d(TAG, "Conectado, descubriendo servicios...");
                gatt.discoverServices();

                if (connectionListener != null) {
                    mainHandler.post(connectionListener::onConnected);
                }

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isConnected = false;
                writeCharacteristic = null;
                Log.d(TAG, "Desconectado");

                if (connectionListener != null) {
                    mainHandler.post(connectionListener::onDisconnected);
                }
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            Log.d(TAG, "Servicios descubiertos, status=" + status);

            BluetoothGattService service = gatt.getService(SERVICE_UUID);
            if (service == null) {
                Log.e(TAG, "Servicio SmartShoe no encontrado");
                if (connectionListener != null) {
                    mainHandler.post(() -> connectionListener.onError("Servicio SmartShoe no encontrado"));
                }
                return;
            }

            writeCharacteristic = service.getCharacteristic(CHARACTERISTIC_UUID);
            if (writeCharacteristic == null) {
                Log.e(TAG, "Characteristic de escritura no encontrada");
                if (connectionListener != null) {
                    mainHandler.post(() -> connectionListener.onError("Characteristic de escritura no encontrada"));
                }
                return;
            }

            Log.d(TAG, "Characteristic de escritura lista");
        }
    };
}
