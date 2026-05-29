import argparse
import json
import os
import hashlib
import numpy as np
import librosa


def file_sha1(path: str, chunk_size: int = 1024 * 1024) -> str:
    h = hashlib.sha1()
    with open(path, "rb") as f:
        while True:
            b = f.read(chunk_size)
            if not b:
                break
            h.update(b)
    return h.hexdigest()


def safe_makedirs_for_file(filepath: str):
    folder = os.path.dirname(filepath)
    if folder:
        os.makedirs(folder, exist_ok=True)


def robust_tempo(y, sr, hop_length=512):
    y_h, y_p = librosa.effects.hpss(y)
    onset_env = librosa.onset.onset_strength(y=y_p, sr=sr, hop_length=hop_length)

    tg = librosa.feature.tempogram(onset_envelope=onset_env, sr=sr, hop_length=hop_length)
    tempi = librosa.tempo_frequencies(tg.shape[0], sr=sr, hop_length=hop_length)
    tg_mean = tg.mean(axis=1)
    tempo_tg = float(tempi[np.argmax(tg_mean)])

    tempo_bt, _ = librosa.beat.beat_track(onset_envelope=onset_env, sr=sr, hop_length=hop_length, trim=False)
    tempo_bt = float(tempo_bt)

    def score(t):
        return abs(t - tempo_bt) * 0.7 + abs(t - tempo_tg) * 0.3 + (0 if 80 <= t <= 140 else 20)

    candidates = [tempo_bt, tempo_tg]
    for t in [tempo_bt / 2, tempo_bt * 2, tempo_tg / 2, tempo_tg * 2]:
        if 40 <= t <= 220:
            candidates.append(float(t))

    best = min(candidates, key=score)
    return best, onset_env, y_p


def snap_beats_to_grid(raw_beats_sec, tempo_bpm, duration, tolerance=0.12):
    if len(raw_beats_sec) < 2:
        return raw_beats_sec

    period = 60.0 / float(tempo_bpm)
    tol = tolerance * period

    start = float(raw_beats_sec[0])
    n = int(np.floor((duration - start) / period)) + 1
    grid = start + np.arange(n) * period

    raw = np.array(raw_beats_sec, dtype=float)

    snapped = []
    used = set()
    for g in grid:
        idx = int(np.argmin(np.abs(raw - g)))
        if idx in used:
            continue
        if abs(raw[idx] - g) <= tol:
            snapped.append(float(raw[idx]))
            used.add(idx)

    snapped = [t for t in snapped if 0 <= t <= duration]
    snapped = sorted(set(snapped))
    return snapped


def compute_strengths(beats_sec, onset_env, sr, hop_length=512):
    frames = librosa.time_to_frames(np.array(beats_sec), sr=sr, hop_length=hop_length)
    frames = np.clip(frames, 0, len(onset_env) - 1)
    vals = onset_env[frames].astype(float)
    if len(vals) == 0:
        return []

    p05, p95 = np.percentile(vals, [5, 95])
    denom = (p95 - p05) if (p95 - p05) > 1e-9 else (vals.max() - vals.min() + 1e-9)
    norm = (vals - p05) / denom
    norm = np.clip(norm, 0.0, 1.0)
    return norm.tolist()


def find_closest_index(times, t):
    arr = np.array(times, dtype=float)
    return int(np.argmin(np.abs(arr - float(t))))


def analyze_audio(audio_path: str, out_path: str = None, one_time: float = None):
    # App contract: keep SR fixed, mono
    y, sr = librosa.load(audio_path, sr=44100, mono=True)
    duration = float(librosa.get_duration(y=y, sr=sr))

    hop_length = 512
    bpm, onset_env, y_p = robust_tempo(y, sr, hop_length=hop_length)

    # Beat tracking on percussive
    _, beat_frames = librosa.beat.beat_track(
        y=y_p, sr=sr, hop_length=hop_length, trim=False, units="frames"
    )
    beat_times = librosa.frames_to_time(beat_frames, sr=sr, hop_length=hop_length).astype(float).tolist()

    if len(beat_times) == 0:
        raise ValueError("No se detectaron beats en el audio.")

    # Clean beats (optional snap)
    beat_times_clean = snap_beats_to_grid(beat_times, bpm, duration, tolerance=0.12)
    if len(beat_times_clean) < max(8, len(beat_times) * 0.4):
        beat_times_clean = beat_times

    strengths = compute_strengths(beat_times_clean, onset_env, sr, hop_length=hop_length)

    # IMPORTANT: phrase-of-8 alignment
    # We define offset_8 so that the chosen downbeat is phrase position 1.
    manual_one_index = None
    offset_8 = 0

    if one_time is not None:
        manual_one_index = find_closest_index(beat_times_clean, one_time)
        offset_8 = (-manual_one_index) % 8
    else:
        # automatic guess: pick position in 1..8 with max weighted strength and align it to 1
        scores = np.zeros(8, dtype=float)
        for i in range(len(beat_times_clean)):
            p = (i % 8)  # 0..7
            s = strengths[i] if i < len(strengths) else 0.0
            scores[p] += s
        best_pos0 = int(np.argmax(scores))  # 0..7
        # make best_pos0 become position 1 => p=0
        offset_8 = (-best_pos0) % 8

    # Build beats output
    beats_out = []
    for i, t in enumerate(beat_times_clean):
        i_global = i + offset_8

        position_in_phrase_8 = int(i_global % 8) + 1
        phrase_index = int(i_global // 8)

        position_in_measure = int(i_global % 4) + 1
        measure = int(i_global // 4) + 1

        strength = float(strengths[i]) if i < len(strengths) else 0.0

        # Strong policy: ONLY the "1" of each 8-count phrase is_strong.
        # This avoids the "no pauses" effect where strong repeats each 4.
        is_strong = (position_in_phrase_8 == 1)

        beats_out.append({
            "index": int(i),
            "time": float(t),
            "measure": int(measure),
            "position_in_measure": int(position_in_measure),
            "phrase_index": int(phrase_index),
            "position_in_phrase_8": int(position_in_phrase_8),
            "strength": strength,
            "is_strong": bool(is_strong),
        })

    # Output contract compatible with your "mi_salsa_beats.json"
    out = {
        # naming aligned to App
        "audioPath": os.path.basename(audio_path),
        "audio_sha1": file_sha1(audio_path),

        "sample_rate": int(sr),
        "duration_sec": float(duration),

        # App-friendly bpm field (keep tempo too for backwards compatibility)
        "bpm": float(bpm),
        "tempo": float(bpm),

        # Keep your offsets but make meaning explicit
        "offset_beats": int(offset_8),
        "manual_one_time": float(one_time) if one_time is not None else None,
        "manual_one_index": int(manual_one_index) if manual_one_index is not None else None,

        "beats": beats_out,
    }

    if out_path:
        safe_makedirs_for_file(out_path)
        with open(out_path, "w", encoding="utf-8") as f:
            json.dump(out, f, ensure_ascii=False, indent=2)

    return out


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("audio", help="Path to audio file (mp3/wav)")
    parser.add_argument("-o", "--out", help="Output json path", default=None)
    parser.add_argument("--one-time", type=float, default=None, help="Manual time (sec) where beat 1 occurs")
    args = parser.parse_args()

    analyze_audio(args.audio, args.out, args.one_time)


if __name__ == "__main__":
    main()


 

    