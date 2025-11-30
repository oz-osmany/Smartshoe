package com.example.smartshoe;

import android.content.Intent;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class PracticeSessionActivity extends AppCompatActivity {

    private TextView textSongTitle, textSongArtist, textSongBpm;
    private ImageView buttonBack, imageAvatar;
    private Button buttonStart, buttonStop;
    private View beatView;

    private PracticeSong selectedSong;
    private MediaPlayer mediaPlayer;
    private int rawResId = 0;

    private Handler beatHandler = new Handler(Looper.getMainLooper());
    private Runnable beatRunnable;
    private boolean isPracticing = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_practice_session);

        // 1) Referencias a la UI
        textSongTitle = findViewById(R.id.textSongTitleSession);
        textSongArtist = findViewById(R.id.textSongArtistSession);
        textSongBpm = findViewById(R.id.textSongBpmSession);
        buttonBack = findViewById(R.id.buttonBackPractice);
        imageAvatar = findViewById(R.id.imageAvatar);
        buttonStart = findViewById(R.id.buttonStartPractice);
        buttonStop = findViewById(R.id.buttonStopPractice);   // 👈 IMPORTANTE
        beatView = findViewById(R.id.viewBeat);

        // 2) Datos de la canción
        Intent intent = getIntent();
        String title = intent.getStringExtra("title");
        String artist = intent.getStringExtra("artist");
        int bpm = intent.getIntExtra("bpm", -1);
        int songIndex = intent.getIntExtra("songIndex", -1);

        selectedSong = new PracticeSong(title, artist, "", bpm);

        textSongTitle.setText(title);
        textSongArtist.setText(artist);
        textSongBpm.setText("BPM: " + bpm);

        // 3) Seleccionar el recurso de audio según la canción
        switch (songIndex) {
            case 0:
                rawResId = R.raw.salsa1;
                break;
            case 1:
                rawResId = R.raw.salsa1;
                break;
            default:
                rawResId = 0;
                break;
        }

        if (rawResId != 0) {
            mediaPlayer = MediaPlayer.create(this, rawResId);
        }

        // 4) Botón volver
        buttonBack.setOnClickListener(v -> {
            stopPracticeCompletely();
            finish();
        });

        // 5) Botón iniciar práctica
        buttonStart.setOnClickListener(v -> {
            if (selectedSong == null) return;
            if (selectedSong.getBpm() <= 0) {
                Toast.makeText(this, "BPM inválido", Toast.LENGTH_SHORT).show();
                return;
            }

            Toast.makeText(this, "Iniciando práctica con " + title, Toast.LENGTH_SHORT).show();

            isPracticing = true;
            startBeatLoop();

            if (mediaPlayer != null) {
                try {
                    if (mediaPlayer.isPlaying()) {
                        mediaPlayer.seekTo(0);
                    } else {
                        mediaPlayer.start();
                    }
                } catch (IllegalStateException e) {
                    e.printStackTrace();
                }
            }
        });

        // 6) Botón detener práctica
        buttonStop.setOnClickListener(v -> {
            Toast.makeText(this, "Práctica detenida", Toast.LENGTH_SHORT).show();
            stopPracticeCompletely();
        });
    }

    // ========= BEAT LOOP =========

    private void startBeatLoop() {
        if (selectedSong == null) return;
        int bpm = selectedSong.getBpm();
        if (bpm <= 0) return;

        long intervalMs = 60000L / bpm;

        if (beatRunnable != null) {
            beatHandler.removeCallbacks(beatRunnable);
        }

        beatRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isPracticing) return;

                // Reset
                beatView.setScaleX(1f);
                beatView.setScaleY(1f);
                beatView.setAlpha(1f);

                // Pulso
                beatView.animate()
                        .scaleX(1.4f)
                        .scaleY(1.4f)
                        .alpha(0.3f)
                        .setDuration(intervalMs / 2)
                        .withEndAction(() -> {
                            beatView.setScaleX(1f);
                            beatView.setScaleY(1f);
                            beatView.setAlpha(1f);
                        });

                beatHandler.postDelayed(this, intervalMs);
            }
        };

        beatHandler.post(beatRunnable);
    }

    private void stopBeatLoop() {
        isPracticing = false;
        if (beatRunnable != null) {
            beatHandler.removeCallbacks(beatRunnable);
        }
        beatView.setScaleX(1f);
        beatView.setScaleY(1f);
        beatView.setAlpha(1f);
    }

    // ========= PARAR TODO (beat + música) =========

    private void stopPracticeCompletely() {
        stopBeatLoop();

        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.pause();
                }
                mediaPlayer.seekTo(0);
            } catch (IllegalStateException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopBeatLoop();
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();
                }
            } catch (IllegalStateException e) {
                e.printStackTrace();
            }
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }
}
