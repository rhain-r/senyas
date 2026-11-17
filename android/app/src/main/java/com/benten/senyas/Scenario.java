package com.benten.senyas;

public class Scenario {
    public final int id;
    public final String title, label, meta;
    public final String[][] signs; // {word, translation}

    public Scenario(int id, String title, String label, String meta, String[][] signs) {
        this.id = id; this.title = title; this.label = label; this.meta = meta;
        this.signs = signs;
    }
}
