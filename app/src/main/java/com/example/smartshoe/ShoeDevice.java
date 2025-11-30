package com.example.smartshoe;

public class ShoeDevice {
    private String id;      // dirección MAC o id BLE
    private String name;    // nombre que se ve al escanear

    public ShoeDevice() {
    }

    public ShoeDevice(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public String getId() { return id; }

    public void setId(String id) { this.id = id; }

    public String getName() { return name; }

    public void setName(String name) { this.name = name; }
}