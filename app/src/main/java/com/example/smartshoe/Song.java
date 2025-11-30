package com.example.smartshoe;

public class Song {
    public int resId;
    public String title;
    public String subtitle;
    public String beatsFile;

    public Song(int resId, String title, String subtitle, String beatsFile) {
        this.resId = resId;
        this.title = title;
        this.subtitle = subtitle;
        this.beatsFile = beatsFile;
    }

    // === GETTERS ===
    public int getResId() {
        return resId;
    }

    public String getTitle() {
        return title;
    }

    public String getSubtitle() {
        return subtitle;
    }

    public String getBeatsFile() {
        return beatsFile;
    }
}
