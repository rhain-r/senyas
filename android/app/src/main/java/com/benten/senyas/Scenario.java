package com.benten.senyas;

import android.content.Context;

/** A practice scenario: an everyday situation and the signs it teaches. Progress lives in Progress. */
public class Scenario {
    public final int id;
    public final String title, label;
    public final int descriptionRes; // string resource, so it follows the app language
    public final String[][] signs;   // {word, translation}

    public final int titleRes, labelRes; // 0 = use the English title / label
    public Scenario(int id, String title, String label, int descriptionRes, String[][] signs) {
        this(id, title, label, 0, 0, descriptionRes, signs);
    }

    public Scenario(int id, String title, String label, int titleRes, int labelRes, int descriptionRes, String[][] signs) {
        this.id = id; this.title = title; this.label = label;
        this.titleRes = titleRes; this.labelRes = labelRes;
        this.descriptionRes = descriptionRes; this.signs = signs;
    }

    /** The title in the app's language. */
    public String title(Context c) {
        return titleRes != 0 ? c.getString(titleRes) : title;
    }

    /** The Practice Room label in the app's language. */
    public String label(Context c) {
        return labelRes != 0 ? c.getString(labelRes) : label;
    }
}
