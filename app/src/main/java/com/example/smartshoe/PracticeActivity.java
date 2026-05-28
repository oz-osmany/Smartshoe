package com.example.smartshoe;


import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class PracticeActivity extends AppCompatActivity {

    private BleShoeManager bleShoeManager;

    private long playbackAnchorElapsedMs = 0L;
    private int playbackAnchorSongMs = 0;
    private long lastDriftCheckElapsedMs = 0L;

    private static final int COMMAND_LEAD_MS = 30;
    private static final int DRIFT_CHECK_INTERVAL_MS = 200;
    private static final int DRIFT_TOLERANCE_MS = 25;
    private static final int BEAT_LOOP_INTERVAL_MS = 5;

    private static final int DEFAULT_VIBRATION_DURATION_MS = 70;
    private static final int DEFAULT_INTENSITY = 180;
    private long espClockOffsetMs = 0L;
    private long toEspTimeMs(long androidElapsedMs) {
        return androidElapsedMs + espClockOffsetMs;
    }
    private static final String TAG = "PracticeActivity";

    private static final UUID SHOE_SERVICE_UUID =
            UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID SHOE_CHARACTERISTIC_UUID =
            UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");

    private static final int REQ_BLUETOOTH_CONNECT = 1001;
    private static final int BEAT_OFFSET_MS = 80;

    private enum DanceStyle {
        SALSA, SON
    }

    private enum ShoeTarget {
        A, B, NONE
    }

    private DanceStyle currentStyle = DanceStyle.SALSA;

    private RadioGroup radioGroupStyle;
    private RadioButton radioSalsa, radioSon;

    private View viewShoeA, viewShoeB;
    private TextView textBeatA, textBeatB;
    private Button btnStartPractice, btnStopPractice;
    private ListView listSongs;

    private MediaPlayer mediaPlayer;
    private AnalysisResult analysisResult;
    private int currentBeatIndex = 0;
    private boolean isRunning = false;
    private boolean dancePatternStarted = false;
    private final Handler beatHandler = new Handler(Looper.getMainLooper());

    private final List<Song> songs = new ArrayList<>();
    private SongAdapter songAdapter;
    private Song selectedSong = null;

    private BluetoothAdapter bluetoothAdapter;


    private ShoeDevice configuredShoeA;

    private int getPredictedSongPositionMs() {
        long now = SystemClock.elapsedRealtime();
        int predicted = playbackAnchorSongMs + (int) (now - playbackAnchorElapsedMs);

        if (mediaPlayer != null && now - lastDriftCheckElapsedMs >= DRIFT_CHECK_INTERVAL_MS) {
            int actual = mediaPlayer.getCurrentPosition();
            int drift = actual - predicted;

            if (Math.abs(drift) > DRIFT_TOLERANCE_MS) {
                playbackAnchorSongMs = actual;
                playbackAnchorElapsedMs = now;
                predicted = actual;
            }

            lastDriftCheckElapsedMs = now;
        }

        return predicted;
    }



    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_practice);

        initViews();
        setupSongs();

        bleShoeManager = new BleShoeManager(this, new BleShoeManager.Listener() {
            @Override
            public void onStatus(String message) {
                Log.d(TAG, "BLE: " + message);
            }

            @Override
            public void onDeviceFound(BluetoothDevice device, String displayText) {
                // No escaneamos desde PracticeActivity
            }

            @Override
            public void onShoeAReady(ShoeDevice shoeDevice) {
                runOnUiThread(() ->
                        Toast.makeText(
                                PracticeActivity.this,
                                "Zapato A conectado: " + shoeDevice.getName(),
                                Toast.LENGTH_SHORT
                        ).show()
                );
            }

            @Override
            public void onShoeBReady(ShoeDevice shoeDevice) {
                runOnUiThread(() ->
                        Toast.makeText(
                                PracticeActivity.this,
                                "Zapato B conectado: " + shoeDevice.getName(),
                                Toast.LENGTH_SHORT
                        ).show()
                );
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() ->
                        Toast.makeText(
                                PracticeActivity.this,
                                message,
                                Toast.LENGTH_SHORT
                        ).show()
                );
            }
        });

        bleShoeManager.connectSavedShoeA(this);
        bleShoeManager.connectSavedShoeB(this);
    }

    private void initViews() {
        viewShoeA = findViewById(R.id.viewShoeA);
        viewShoeB = findViewById(R.id.viewShoeB);
        textBeatA = findViewById(R.id.textBeatA);
        textBeatB = findViewById(R.id.textBeatB);
        btnStartPractice = findViewById(R.id.btnStartPractice);
        btnStopPractice = findViewById(R.id.btnStopPractice);
        listSongs = findViewById(R.id.listSongs);

        radioGroupStyle = findViewById(R.id.radioGroupStyle);
        radioSalsa = findViewById(R.id.radioSalsa);
        radioSon = findViewById(R.id.radioSon);

        radioGroupStyle.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.radioSalsa) {
                currentStyle = DanceStyle.SALSA;
            } else if (checkedId == R.id.radioSon) {
                currentStyle = DanceStyle.SON;
            }
        });

        btnStartPractice.setOnClickListener(v -> startPractice());
        btnStopPractice.setOnClickListener(v -> stopPractice());

        btnStartPractice.setOnLongClickListener(v -> {
            sendStepToShoe(ShoeTarget.A, 0, false);
            return true;
        });

        listSongs.setOnItemClickListener((parent, view, position, id) -> {
            Song song = songs.get(position);
            onSongSelected(song);
        });
    }


    private void setupSongs() {
        songs.add(new Song(R.raw.salsa1, "Salsa 1", "Artista • 3:25", "beats/mi_salsa_beats.json"));
        songs.add(new Song(R.raw.salsa2, "Salsa 2", "Artista • 4:02", "beats/mi_salsa_beats2.json"));
        songs.add(new Song(R.raw.toda_una_vida, "Salsa 3", "Artista • 3:48", "beats/mi_salsa_beats3.json"));
        songs.add(new Song(R.raw.salsa4, "Salsa 4", "Artista • 3:48", "beats/mi_salsa_beats4.json"));
        songs.add(new Song(R.raw.salsa5, "Salsa 5", "Artista • 3:48", "beats/mi_salsa_beats5.json"));

        songAdapter = new SongAdapter(this, songs);
        listSongs.setAdapter(songAdapter);
    }

    private ShoeDevice loadConfiguredShoeA() {
        SharedPreferences prefs = getSharedPreferences("user_profile", MODE_PRIVATE);
        String id = prefs.getString("leftShoeId", null);
        String name = prefs.getString("leftShoeName", null);

        if (id == null || id.trim().isEmpty()) {
            return null;
        }

        return new ShoeDevice(id, name != null ? name : "Zapato A");
    }

    private void initBluetooth() {
        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (manager != null) {
            bluetoothAdapter = manager.getAdapter();
        }

        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth no disponible en este dispositivo", Toast.LENGTH_LONG).show();
        }
    }

    @SuppressLint("MissingPermission")
//    private void connectToConfiguredShoeA() {
//        if (configuredShoeA == null) {
//            Toast.makeText(this, "Primero enlaza el zapato A desde la pantalla de inicio", Toast.LENGTH_LONG).show();
//            return;
//        }
//
//        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
//            Toast.makeText(this, "Activa el Bluetooth", Toast.LENGTH_SHORT).show();
//            return;
//        }
//
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
//            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
//                    != PackageManager.PERMISSION_GRANTED) {
//                ActivityCompat.requestPermissions(
//                        this,
//                        new String[]{Manifest.permission.BLUETOOTH_CONNECT},
//                        REQ_BLUETOOTH_CONNECT
//                );
//                return;
//            }
//        }
//
//        if (isConnectingA || gattA != null) {
//            return;
//        }
//
//        BluetoothDevice deviceA = bluetoothAdapter.getRemoteDevice(configuredShoeA.getId());
//        if (deviceA == null) {
//            Toast.makeText(this, "No se pudo recuperar el zapato A guardado", Toast.LENGTH_SHORT).show();
//            return;
//        }
//
//        isConnectingA = true;
//        gattA = deviceA.connectGatt(this, false, gattCallbackA);
//        Log.d(TAG, "Conectando a zapato A: " + configuredShoeA.getId());
//    }

    private final BluetoothGattCallback gattCallbackA = new BluetoothGattCallback() {
        //@Override
//        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
//            if (status != BluetoothGatt.GATT_SUCCESS) {
//                Log.e(TAG, "Error GATT A. status=" + status);
//                safeCloseGattA();
//                return;
//            }
//
//            if (newState == BluetoothGatt.STATE_CONNECTED) {
//                Log.d(TAG, "Zapato A conectado. Descubriendo servicios...");
//                isConnectingA = false;
//                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
//                    if (ActivityCompat.checkSelfPermission(
//                            PracticeActivity.this,
//                            Manifest.permission.BLUETOOTH_CONNECT
//                    ) != PackageManager.PERMISSION_GRANTED) {
//                        return;
//                    }
//                }
//
//                gatt.discoverServices();
//            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
//                Log.d(TAG, "Zapato A desconectado.");
//                safeCloseGattA();
//            }
//        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Error descubriendo servicios A: " + status);
                return;
            }

            BluetoothGattService service = gatt.getService(SHOE_SERVICE_UUID);
            if (service == null) {
                Log.e(TAG, "Servicio no encontrado en zapato A");
                runOnUiThread(() ->
                        Toast.makeText(PracticeActivity.this,
                                "Zapato A sin servicio BLE esperado",
                                Toast.LENGTH_LONG).show());
                return;
            }

            BluetoothGattCharacteristic characteristic =
                    service.getCharacteristic(SHOE_CHARACTERISTIC_UUID);

            if (characteristic == null) {
                Log.e(TAG, "Característica no encontrada en zapato A");
                runOnUiThread(() ->
                        Toast.makeText(PracticeActivity.this,
                                "Zapato A sin característica de control",
                                Toast.LENGTH_LONG).show());
                return;
            }

            characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            //charA = characteristic;

            Log.d(TAG, "Zapato A listo para recibir comandos");
            runOnUiThread(() ->
                    Toast.makeText(PracticeActivity.this,
                            "Zapato A conectado y listo",
                            Toast.LENGTH_SHORT).show());
        }
    };

//    private void safeCloseGattA() {
//
//        isConnectingA = false;
//        charA = null;
//
//        if (gattA != null) {
//
//            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
//                if (ActivityCompat.checkSelfPermission(
//                        this,
//                        Manifest.permission.BLUETOOTH_CONNECT
//                ) != PackageManager.PERMISSION_GRANTED) {
//                    gattA = null;
//                    return;
//                }
//            }
//
//            try {
//                gattA.close();
//            } catch (Exception ignored) {
//            }
//
//            gattA = null;
//        }
//    }

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
        is.close();

        return JsonParsers.parseAnalysisResult(json);
    }

    private void startPractice() {
        if (selectedSong == null) {
            Toast.makeText(this, "Selecciona una canción", Toast.LENGTH_SHORT).show();
            return;
        }

        if (analysisResult == null || analysisResult.beats == null || analysisResult.beats.isEmpty()) {
            Toast.makeText(this, "No hay beats analizados", Toast.LENGTH_SHORT).show();
            return;
        }

        if (bleShoeManager == null || !bleShoeManager.isShoeAReady()) {
            Toast.makeText(this, "Zapato A aún no está listo", Toast.LENGTH_SHORT).show();
            return;
        }

        if (mediaPlayer == null) {
            mediaPlayer = MediaPlayer.create(this, selectedSong.resId);
        }

        currentBeatIndex = 0;
        dancePatternStarted = false;
        resetShoesVisual();

        mediaPlayer.seekTo(0);
        mediaPlayer.start();

        playbackAnchorElapsedMs = SystemClock.elapsedRealtime();
        playbackAnchorSongMs = 0;
        lastDriftCheckElapsedMs = playbackAnchorElapsedMs;

        isRunning = true;
        beatHandler.removeCallbacks(beatRunnable);
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
            } catch (Exception ignored) {
            }
            mediaPlayer = null;
        }
    }

    private final Runnable beatRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRunning || mediaPlayer == null || !mediaPlayer.isPlaying()) {
                return;
            }

            if (analysisResult == null || analysisResult.beats == null) {
                return;
            }

            int predictedSongPositionMs = getPredictedSongPositionMs();

            while (currentBeatIndex < analysisResult.beats.size()) {
                Beat beat = analysisResult.beats.get(currentBeatIndex);
                int beatTimeMs = (int) (beat.time * 1000.0);

                if (predictedSongPositionMs >= (beatTimeMs - COMMAND_LEAD_MS)) {
                    triggerBeatAnimation(beat);
                    currentBeatIndex++;
                } else {
                    break;
                }
            }

            beatHandler.postDelayed(this, BEAT_LOOP_INTERVAL_MS);
        }
    };

    private void triggerBeatAnimation(Beat beat) {
        int p = beat.position_in_phrase_8;
        boolean strong = (beat.position_in_phrase_8 == 1) && beat.is_strong;

        textBeatA.setText("A: -");
        textBeatB.setText("B: -");

        // Esperar hasta el primer 8 real antes de empezar a enviar señales
        if (!dancePatternStarted) {
            if (p == 8) {
                dancePatternStarted = true;
                Log.d(TAG, "Patrón iniciado en el primer 8 real.");
            } else {
                Log.d(TAG, "Esperando primer 8. Beat actual=" + p);
                return;
            }
        }

        if (currentStyle == DanceStyle.SALSA) {
            if (p == 8 || p == 2 || p == 5) {
                animateShoe(viewShoeA);
                textBeatA.setText("A: " + p);
                sendStepToShoe(ShoeTarget.A, p, strong);
            }

            if (p == 1 || p == 4 || p == 6) {
                animateShoe(viewShoeB);
                textBeatB.setText("B: " + p);
                sendStepToShoe(ShoeTarget.B, p, strong);
            }
        } else {
            if (p == 8 || p == 2 || p == 5) {
                animateShoe(viewShoeA);
                textBeatA.setText("A: " + p);
                sendStepToShoe(ShoeTarget.A, p, strong);
            }

            if (p == 7 || p == 1 || p == 4) {
                animateShoe(viewShoeB);
                textBeatB.setText("B: " + p);
                sendStepToShoe(ShoeTarget.B, p, strong);
            }
        }

        Log.d(TAG, "Beat index=" + beat.index +
                " phrasePos=" + p +
                " style=" + currentStyle.name());
    }

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

        textBeatA.setText("A: -");
        textBeatB.setText("B: -");
    }

    @SuppressLint("MissingPermission")
    private void sendStepToShoe(
            ShoeTarget shoeTarget,
            int danceCount,
            boolean strong
    ) {
        if (bleShoeManager == null) {
            Log.w(TAG, "bleShoeManager nulo");
            return;
        }

        int durationMs = strong ? 90 : DEFAULT_VIBRATION_DURATION_MS;
        int intensity = strong ? 220 : DEFAULT_INTENSITY;

        int delayMs = COMMAND_LEAD_MS;

        String payload =
                "D," +
                        delayMs +
                        "," +
                        durationMs +
                        "," +
                        intensity;

        boolean ok = false;

        if (shoeTarget == ShoeTarget.A) {
            ok = bleShoeManager.sendToShoeA(this, payload);
        } else if (shoeTarget == ShoeTarget.B) {
            ok = bleShoeManager.sendToShoeB(this, payload);
        }

        Log.d(
                TAG,
                "Beat enviado -> shoe=" +
                        shoeTarget +
                        " | danceCount=" +
                        danceCount +
                        " | strong=" +
                        strong +
                        " | payload=" +
                        payload +
                        " | ok=" +
                        ok
        );
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQ_BLUETOOTH_CONNECT) {

            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {

                if (bleShoeManager != null) {
                    bleShoeManager.connectSavedShoeA(this);
                }

            } else {

                Toast.makeText(
                        this,
                        "No se puede conectar al zapato sin permiso Bluetooth",
                        Toast.LENGTH_LONG
                ).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPractice();
        releaseMediaPlayer();

        if (bleShoeManager != null) {
            bleShoeManager.close(this);
        }
    }
}