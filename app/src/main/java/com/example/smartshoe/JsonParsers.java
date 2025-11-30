package com.example.smartshoe;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;

public class JsonParsers {

    private static final Gson gson = new Gson();

    public static AnalysisResult parseAnalysisResult(String json) {
        Type type = new TypeToken<AnalysisResult>() {}.getType();
        return gson.fromJson(json, type);
    }
}
