package com.benten.senyas;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class Lesson {
    public int id;
    public String title, tutor, initials, category, description;
    public double distanceKm;
    public int price, duration, previewSeconds, tileColor;
    public boolean available, darkText;

    // Tutor-side fields
    public String city = "Quezon City";
    /** Weekdays the tutor teaches (see Availability) and session start times, minutes after midnight. */
    public int days = Availability.MON_TO_SAT;
    public List<Integer> times = new ArrayList<>(Arrays.asList(9 * 60, 10 * 60, 14 * 60, 15 * 60, 16 * 60));
    public String created = "13 October 2026";
    public boolean mine, hasCover = true, hasPreview = true;
    public boolean reviewed = true; // false until an admin approves a newly published listing

    public Lesson(int id, String title, String tutor, String initials, String category,
                  double distanceKm, int price, int duration, boolean available,
                  String description, int previewSeconds, int tileColor, boolean darkText) {
        this.id = id; this.title = title; this.tutor = tutor; this.initials = initials;
        this.category = category; this.distanceKm = distanceKm; this.price = price;
        this.duration = duration; this.available = available; this.description = description;
        this.previewSeconds = previewSeconds; this.tileColor = tileColor; this.darkText = darkText;
    }
}
