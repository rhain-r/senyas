package com.benten.senyas;

import androidx.annotation.ColorRes;

/**
 * Implemented by tab screens that want the status-bar strip painted in their header colour
 * (the blue Home and Path headers). MainActivity reads it whenever the screen resumes.
 */
public interface StatusTint {
    @ColorRes int statusColor();
}
