package com.example.smartshoe;

public class PracticeSong {
    private String title;
    private String artist;
    private String url;
    private int bpm;

    public PracticeSong(String title, String artist, String url, int bpm) {
        this.title = title;
        this.artist = artist;
        this.url = url;
        this.bpm = bpm;
    }

    public String getTitle() { return title; }
    public String getArtist() { return artist; }
    public String getUrl() { return url; }
    public int getBpm() { return bpm; }
}
