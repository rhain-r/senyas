package com.benten.senyas;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** What one account keeps on this device: practice progress, its bookings and its notifications. */
final class AccountData {
    Progress progress = new Progress();
    final List<Repo.Booking> bookings = new ArrayList<>();
    final List<Repo.Notif> notifs = new ArrayList<>();

    String toJson() {
        try {
            JSONArray b = new JSONArray();
            for (Repo.Booking x : bookings) b.put(Records.booking(x));
            return new JSONObject().put("v", 1).put("progress", progress.toJsonObject())
                    .put("bookings", b).put("notifs", Records.notifs(notifs)).toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Empty data for a new account, or when nothing readable is saved. */
    static AccountData fromJson(@Nullable String json) {
        AccountData d = new AccountData();
        if (json == null) return d;
        try {
            JSONObject o = new JSONObject(json);
            d.progress = Progress.from(o.optJSONObject("progress"));
            JSONArray b = o.optJSONArray("bookings");
            if (b != null) {
                for (int i = 0; i < b.length(); i++) {
                    JSONObject x = b.optJSONObject(i);
                    if (x != null) d.bookings.add(Records.booking(x));
                }
            }
            Records.readNotifs(o.optJSONArray("notifs"), d.notifs);
        } catch (JSONException e) {
            return new AccountData();
        }
        return d;
    }
}
