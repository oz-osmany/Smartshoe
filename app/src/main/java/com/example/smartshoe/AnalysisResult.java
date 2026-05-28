package com.example.smartshoe;
import com.google.gson.annotations.SerializedName;
// AnalysisResult.java
import java.util.List;

public class AnalysisResult {
    @SerializedName(value="audioPath", alternate={"audio_path"})
    public String audioPath;

    @SerializedName(value="bpm", alternate={"tempo"})
    public double bpm;

    public List<Beat> beats;
    public List<Section> sections;
}
