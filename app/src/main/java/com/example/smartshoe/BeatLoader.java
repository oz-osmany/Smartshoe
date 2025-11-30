package com.example.smartshoe;

// BeatLoader.java
import android.content.Context;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

public class BeatLoader {

    public static AnalysisResult loadAnalysisFromAssets(Context context, String assetPath) throws IOException {
        InputStream is = context.getAssets().open(assetPath);
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;

        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }

        reader.close();
        is.close();

        String json = sb.toString();

        Gson gson = new GsonBuilder().create();
        return gson.fromJson(json, AnalysisResult.class);
    }
}
