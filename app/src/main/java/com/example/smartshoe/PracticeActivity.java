package com.example.smartshoe;

import androidx.appcompat.app.AppCompatActivity;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import android.annotation.SuppressLint;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Pantalla de práctica:
 * - Selecciona canción.
 * - Carga beats desde JSON (AnalysisResult).
 * - Marca pasos con patrón: 8A,1B,2A,3B,4P,5A,6B,7P.
 * - Envía comandos por BLE al zapato (ESP32).
 */
public class PracticeActivity extends AppCompatActivity {

    private static final String TAG = "PracticeActivity";

    // ⚠️ AJUSTA ESTOS VALORES A TU ESP32
    private static final String SHOE_DEVICE_ADDRESS = "78:E3:6D:0B:4C:96"; // MAC del ESP32
    private static final UUID SHOE_SERVICE_UUID =
            UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID SHOE_CHARACTERISTIC_UUID =
            UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");

    private static final int BEAT_OFFSET_MS = 80;
    private static final int REQ_BLUETOOTH_CONNECT = 1001;

    // Vistas
    private View viewShoeA, viewShoeB;
    private TextView textBeatA, textBeatB;
    private Button btnStartPractice, btnStopPractice;
    private ListView listSongs;

    // Audio / beats
    private MediaPlayer mediaPlayer;
    private AnalysisResult analysisResult;
    private int currentBeatIndex = 0;
    private boolean isRunning = false;
    private final Handler beatHandler = new Handler(Looper.getMainLooper());

    // Canciones
    private final List<Song> songs = new ArrayList<>();
    private SongAdapter songAdapter;
    private Song selectedSong = null;

    private enum ShoeTarget { A, B, NONE }

    // BLE
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothGatt bluetoothGatt;
    private BluetoothGattCharacteristic controlCharacteristic;
    private boolean isBleConnected = false;

    // ─────────────────────────────
    // CICLO DE VIDA
    // ─────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_practice);

        // Vistas
        viewShoeA = findViewById(R.id.viewShoeA);
        viewShoeB = findViewById(R.id.viewShoeB);
        textBeatA = findViewById(R.id.textBeatA);
        textBeatB = findViewById(R.id.textBeatB);
        btnStartPractice = findViewById(R.id.btnStartPractice);
        btnStopPractice = findViewById(R.id.btnStopPractice);
        listSongs = findViewById(R.id.listSongs);

        // Canciones (ajusta tus recursos y JSON)
        songs.add(new Song(
                R.raw.salsa1,
                "Salsa 1",
                "Artista • 3:25",
                "beats/mi_salsa_beats.json"
        ));
        songs.add(new Song(
                R.raw.salsa1,
                "Salsa 2",
                "Artista • 4:02",
                "beats/mi_salsa_beats.json"
        ));
        songs.add(new Song(
                R.raw.salsa1,
                "Salsa 3",
                "Artista • 3:48",
                "beats/mi_salsa_beats.json"
        ));

        songAdapter = new SongAdapter(this, songs);
        listSongs.setAdapter(songAdapter);

        listSongs.setOnItemClickListener((parent, view, position, id) -> {
            Song song = songs.get(position);
            onSongSelected(song);
        });

        btnStartPractice.setOnClickListener(v -> startPractice());
        btnStopPractice.setOnClickListener(v -> stopPractice());

        initBluetooth();
        connectToShoe(); // puedes quitarlo si quieres conectar solo desde un botón
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPractice();
        releaseMediaPlayer();
        disconnectBle();
    }

    // ─────────────────────────────
    // CARGA DE CANCIÓN / JSON
    // ─────────────────────────────

    public void onSongSelected(Song song) {
        stopPractice();
        releaseMediaPlayer();

        selectedSong = song;

        try {
            analysisResult = loadAnalysisFromAssets(song.beatsFile);
            currentBeatIndex = 0;
            Toast.makeText(this, "Canción: " + song.title, Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(this, "Error cargando beats: " + e.getMessage(), Toast.LENGTH_LONG).show();
            analysisResult = null;
        }

        mediaPlayer = MediaPlayer.create(this, song.resId);
    }

    // Si quieres botón play desde el adapter
    public void onSongPlayClicked(Song song) {
        onSongSelected(song);
        startPractice();
    }

    private AnalysisResult loadAnalysisFromAssets(String pathInAssets) throws IOException {
        InputStream is = getAssets().open(pathInAssets);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] data = new byte[4096];
        int nRead;
        while ((nRead = is.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }
        String json = buffer.toString("UTF-8");

        // Usa tu propia clase de parseo (Gson)
        return JsonParsers.parseAnalysisResult(json);
    }

    // ─────────────────────────────
    // START / STOP PRÁCTICA
    // ─────────────────────────────

    private void startPractice() {
        if (selectedSong == null) {
            Toast.makeText(this, "Selecciona una canción", Toast.LENGTH_SHORT).show();
            return;
        }
        if (analysisResult == null || analysisResult.beats == null || analysisResult.beats.isEmpty()) {
            Toast.makeText(this, "No hay beats analizados", Toast.LENGTH_SHORT).show();
            return;
        }
        if (mediaPlayer == null) {
            mediaPlayer = MediaPlayer.create(this, selectedSong.resId);
        }

        currentBeatIndex = 0;
        resetShoesVisual();

        mediaPlayer.seekTo(0);
        mediaPlayer.start();

        isRunning = true;
        beatHandler.post(beatRunnable);
    }

    private void stopPractice() {
        isRunning = false;
        beatHandler.removeCallbacks(beatRunnable);

        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            mediaPlayer.seekTo(0);
        }

        resetShoesVisual();
    }

    private void releaseMediaPlayer() {
        if (mediaPlayer != null) {
            try {
                mediaPlayer.release();
            } catch (Exception ignored) {}
            mediaPlayer = null;
        }
    }

    // ─────────────────────────────
    // BUCLE DE BEATS
    // ─────────────────────────────

    private final Runnable beatRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRunning || mediaPlayer == null || !mediaPlayer.isPlaying()) {
                return;
            }
            if (analysisResult == null || analysisResult.beats == null) {
                return;
            }

            int currentPositionMs = mediaPlayer.getCurrentPosition();

            while (currentBeatIndex < analysisResult.beats.size()) {
                Beat beat = analysisResult.beats.get(currentBeatIndex);
                int beatTimeMs = (int) (beat.time * 1000.0);

                if (currentPositionMs >= beatTimeMs - BEAT_OFFSET_MS) {
                    triggerBeatAnimation(beat);
                    currentBeatIndex++;
                } else {
                    break;
                }
            }

            beatHandler.postDelayed(this, 10);
        }
    };

    // ─────────────────────────────
    // LÓGICA 8A,1B,2A,3B,4P,5A,6B,7P
    // ─────────────────────────────

    // JSON 1 -> baile 1, 2 -> 3, 3 -> 5, 4 -> 7, etc.
    private int getOddDanceCountForBeat(Beat beat) {
        int p = beat.position_in_phrase_8;   // 1..8
        int odd = 2 * (p - 1) + 1;          // 1,3,5,7,9,...
        return ((odd - 1) % 8) + 1;         // normalizado 1..8
    }

    // Tu patrón: 8A, 1B, 2A, 3B, 4 pausa, 5A, 6B, 7 pausa
    private ShoeTarget getShoeTargetForDanceCount(int count) {
        switch (count) {
            case 1: return ShoeTarget.B;
            case 2: return ShoeTarget.A;
            case 3: return ShoeTarget.B;
            case 4: return ShoeTarget.NONE;
            case 5: return ShoeTarget.A;
            case 6: return ShoeTarget.B;
            case 7: return ShoeTarget.NONE;
            case 8: return ShoeTarget.A;
            default: return ShoeTarget.NONE;
        }
    }

    private void triggerBeatAnimation(Beat beat) {
        int oddCount = getOddDanceCountForBeat(beat);   // 1,3,5,7 → 1..8
        ShoeTarget oddTarget = getShoeTargetForDanceCount(oddCount);

        // Paso IMPAR (1,3,5,7)
        switch (oddTarget) {
            case A:
                animateShoe(viewShoeA);
                if (textBeatA != null) textBeatA.setText("A: " + oddCount);
                sendStepToShoe(oddTarget, oddCount, beat.is_strong);
                break;
            case B:
                animateShoe(viewShoeB);
                if (textBeatB != null) textBeatB.setText("B: " + oddCount);
                sendStepToShoe(oddTarget, oddCount, beat.is_strong);
                break;
            case NONE:
                if (textBeatA != null) textBeatA.setText("A: -");
                if (textBeatB != null) textBeatB.setText("B: -");
                break;
        }

        // Paso PAR intermedio (2,4,6,8) entre este beat y el siguiente
        int nextIndex = beat.index + 1;
        if (analysisResult != null &&
                analysisResult.beats != null &&
                nextIndex < analysisResult.beats.size()) {

            Beat nextBeat = analysisResult.beats.get(nextIndex);

            long t1Ms = (long) (beat.time * 1000.0);
            long t2Ms = (long) (nextBeat.time * 1000.0);
            long tMidMs = (t1Ms + t2Ms) / 2;

            int evenCount = (oddCount % 8) + 1; // 1->2, 3->4, 5->6, 7->8
            ShoeTarget evenTarget = getShoeTargetForDanceCount(evenCount);

            scheduleEvenStep(tMidMs, evenCount, evenTarget);
        }
    }

    private void scheduleEvenStep(long eventTimeMs, int evenCount, ShoeTarget target) {
        if (target == ShoeTarget.NONE) return;
        if (mediaPlayer == null) return;

        long nowMs = mediaPlayer.getCurrentPosition();
        long delay = eventTimeMs - nowMs;
        if (delay < 0) return;

        beatHandler.postDelayed(() -> {
            if (!isRunning || mediaPlayer == null || !mediaPlayer.isPlaying()) {
                return;
            }

            switch (target) {
                case A:
                    animateShoe(viewShoeA);
                    if (textBeatA != null) textBeatA.setText("A: " + evenCount);
                    sendStepToShoe(ShoeTarget.A, evenCount, false);
                    break;
                case B:
                    animateShoe(viewShoeB);
                    if (textBeatB != null) textBeatB.setText("B: " + evenCount);
                    sendStepToShoe(ShoeTarget.B, evenCount, false);
                    break;
                case NONE:
                    break;
            }
        }, delay);
    }

    // ─────────────────────────────
    // ANIMACIONES VISUALES
    // ─────────────────────────────

    private void animateShoe(View shoeView) {
        if (shoeView == null) return;

        shoeView.animate()
                .scaleX(1.2f)
                .scaleY(1.2f)
                .alpha(0.7f)
                .setDuration(120)
                .withEndAction(() -> shoeView.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .alpha(1f)
                        .setDuration(120)
                        .start())
                .start();
    }

    private void resetShoesVisual() {
        if (viewShoeA != null) {
            viewShoeA.setScaleX(1f);
            viewShoeA.setScaleY(1f);
            viewShoeA.setAlpha(1f);
        }
        if (viewShoeB != null) {
            viewShoeB.setScaleX(1f);
            viewShoeB.setScaleY(1f);
            viewShoeB.setAlpha(1f);
        }
        if (textBeatA != null) textBeatA.setText("A: -");
        if (textBeatB != null) textBeatB.setText("B: -");
    }

    // ─────────────────────────────
    // BLE: PERMISOS + CONEXIÓN
    // ─────────────────────────────

    private void initBluetooth() {
        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (manager != null) {
            bluetoothAdapter = manager.getAdapter();
        }
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth no disponible en este dispositivo", Toast.LENGTH_LONG).show();
        }
    }

    private boolean hasBlePermission(boolean requestIfNeeded) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true; // antes de Android 12 no hace falta BLUETOOTH_CONNECT
        }

        int granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
        );
        if (granted == PackageManager.PERMISSION_GRANTED) {
            return true;
        }

        if (requestIfNeeded) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.BLUETOOTH_CONNECT},
                    REQ_BLUETOOTH_CONNECT
            );
        }
        return false;
    }

    @SuppressLint("MissingPermission")
    private void connectToShoe() {
        if (bluetoothAdapter == null) return;

        // Android 12+ → comprobar permiso justo antes de usar Bluetooth
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED) {

                ActivityCompat.requestPermissions(
                        this,
                        new String[]{Manifest.permission.BLUETOOTH_CONNECT},
                        REQ_BLUETOOTH_CONNECT
                );
                Log.w(TAG, "Esperando permiso BLUETOOTH_CONNECT");
                return;
            }
        }

        BluetoothDevice device = bluetoothAdapter.getRemoteDevice(SHOE_DEVICE_ADDRESS);
        if (device == null) {
            Toast.makeText(this, "No se encontró el dispositivo del zapato", Toast.LENGTH_LONG).show();
            return;
        }

        // ✅ Aquí ya tenemos permiso → Lint deja de quejarse
        bluetoothGatt = device.connectGatt(this, false, gattCallback);
        Log.d(TAG, "Conectando a zapato BLE...");
    }


    @SuppressLint("MissingPermission")
    private void disconnectBle() {
        isBleConnected = false;

        if (bluetoothGatt != null) {

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ActivityCompat.checkSelfPermission(
                        this,
                        Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED) {
                    // No tenemos permiso, solo soltamos la referencia
                    bluetoothGatt = null;
                    return;
                }
            }

            bluetoothGatt.close();   // ✅ ya no da warning
            bluetoothGatt = null;
        }
    }


    // Respuesta de permisos
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQ_BLUETOOTH_CONNECT) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Permiso BLUETOOTH_CONNECT concedido, reconectando...");
                connectToShoe();
            } else {
                Log.w(TAG, "Permiso BLUETOOTH_CONNECT denegado.");
                Toast.makeText(this,
                        "No se puede conectar al zapato sin permiso de Bluetooth",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    // ─────────────────────────────
    // CALLBACK BLE
    // ─────────────────────────────

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            super.onConnectionStateChange(gatt, status, newState);

            if (newState == BluetoothGatt.STATE_CONNECTED) {
                Log.d(TAG, "BLE conectado, descubriendo servicios...");

                if (!hasBlePermission(false)) {
                    Log.w(TAG, "Sin permiso BLUETOOTH_CONNECT para discoverServices()");
                    return;
                }

                isBleConnected = true;
                gatt.discoverServices();
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                Log.d(TAG, "BLE desconectado");
                isBleConnected = false;
                controlCharacteristic = null;
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            super.onServicesDiscovered(gatt, status);

            if (!hasBlePermission(false)) {
                Log.w(TAG, "Sin permiso BLUETOOTH_CONNECT en onServicesDiscovered");
                return;
            }

            BluetoothGattService service = gatt.getService(SHOE_SERVICE_UUID);
            if (service != null) {
                controlCharacteristic = service.getCharacteristic(SHOE_CHARACTERISTIC_UUID);
                if (controlCharacteristic != null) {
                    Log.d(TAG, "Característica de control encontrada");
                } else {
                    Log.d(TAG, "Característica de control NO encontrada");
                }
            } else {
                Log.d(TAG, "Servicio del zapato NO encontrado");
            }
        }
    };

    // ─────────────────────────────
    // ENVÍO DE COMANDOS AL ZAPATO
    // ─────────────────────────────
    @SuppressLint("MissingPermission")
    private void sendStepToShoe(ShoeTarget shoeTarget, int danceCount, boolean strong) {
        if (!isBleConnected || bluetoothGatt == null || controlCharacteristic == null) {
            return;
        }
        if (!hasBlePermission(false)) {
            Log.w(TAG, "Sin permiso BLUETOOTH_CONNECT, no se puede enviar comando.");
            return;
        }

        byte shoeByte;
        switch (shoeTarget) {
            case A:
                shoeByte = 0x01;
                break;
            case B:
                shoeByte = 0x02;
                break;
            default:
                return;
        }

        byte intensityByte = strong ? (byte) 0x02 : (byte) 0x01;
        byte stepByte = (byte) danceCount;

        byte[] payload = new byte[]{shoeByte, intensityByte, stepByte};

        controlCharacteristic.setValue(payload);
        boolean ok = bluetoothGatt.writeCharacteristic(controlCharacteristic);

        if (!ok) {
            Log.w(TAG, "Fallo al escribir característica BLE");
        } else {
            Log.d(TAG, "Comando enviado: shoe=" + shoeByte +
                    " intensity=" + intensityByte +
                    " step=" + stepByte);
        }
    }
}
