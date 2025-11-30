package com.example.smartshoe;

public class UserProfile {

    public enum Role {
        WOMAN,
        MAN
    }

    public enum StartFoot {
        LEFT,
        RIGHT
    }

    private String name;
    private Role role;
    private StartFoot startFoot;
    private ShoeDevice leftShoe;
    private ShoeDevice rightShoe;
    private int vibrationIntensity; // 0–100

    public UserProfile() {
    }

    public UserProfile(String name,
                       Role role,
                       StartFoot startFoot,
                       ShoeDevice leftShoe,
                       ShoeDevice rightShoe,
                       int vibrationIntensity) {
        this.name = name;
        this.role = role;
        this.startFoot = startFoot;
        this.leftShoe = leftShoe;
        this.rightShoe = rightShoe;
        this.vibrationIntensity = vibrationIntensity;
    }

    public String getName() { return name; }

    public void setName(String name) { this.name = name; }

    public Role getRole() { return role; }

    public void setRole(Role role) { this.role = role; }

    public StartFoot getStartFoot() { return startFoot; }

    public void setStartFoot(StartFoot startFoot) { this.startFoot = startFoot; }

    public ShoeDevice getLeftShoe() { return leftShoe; }

    public void setLeftShoe(ShoeDevice leftShoe) { this.leftShoe = leftShoe; }

    public ShoeDevice getRightShoe() { return rightShoe; }

    public void setRightShoe(ShoeDevice rightShoe) { this.rightShoe = rightShoe; }

    public int getVibrationIntensity() { return vibrationIntensity; }

    public void setVibrationIntensity(int vibrationIntensity) {
        this.vibrationIntensity = vibrationIntensity;
    }
}
