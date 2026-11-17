package com.benten.senyas;

import androidx.annotation.ColorRes;

/** Implemented by screens that want the status-bar area painted in a specific colour. */
public interface StatusTint {
    @ColorRes int statusColor();
}
