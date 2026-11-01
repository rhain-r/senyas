package com.benten.senyas;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.util.AttributeSet;
import android.view.Surface;
import android.view.TextureView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;

/**
 * Plays one sign clip from assets: muted, looping, fitted inside the view (no cropping, so the
 * whole signing space stays visible). A TextureView, so a parent with rounded corners clips it.
 */
public class SignVideoView extends TextureView implements TextureView.SurfaceTextureListener {

    @Nullable private MediaPlayer player;
    @Nullable private Surface surface;
    @Nullable private String asset;
    private boolean prepared;
    private float speed = 1f;
    private int videoWidth, videoHeight;

    public SignVideoView(Context c, @Nullable AttributeSet a) {
        super(c, a);
        setSurfaceTextureListener(this);
    }

    /** An asset path such as "signs/fsl105/salamat.mp4". */
    public void setAsset(@Nullable String path) {
        asset = path;
        if (surface != null) open();
    }

    /** 1 for normal speed, 0.5 to see the movement clearly. */
    public void setSpeed(float s) {
        speed = s;
        applySpeed();
    }

    public void replay() {
        if (player == null || !prepared) return;
        player.seekTo(0);
        player.start();
        applySpeed();
    }

    private void open() {
        release();
        if (asset == null || surface == null) return;
        MediaPlayer p = new MediaPlayer();
        try (AssetFileDescriptor fd = getContext().getAssets().openFd(asset)) {
            p.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
        } catch (IOException e) {
            p.release();
            return;
        }
        p.setSurface(surface);
        p.setLooping(true);
        p.setVolume(0f, 0f);
        p.setOnPreparedListener(mp -> {
            prepared = true;
            videoWidth = mp.getVideoWidth();
            videoHeight = mp.getVideoHeight();
            fit();
            mp.start();
            applySpeed();
        });
        p.prepareAsync();
        player = p;
    }

    private void applySpeed() {
        if (player == null || !prepared) return;
        try {
            player.setPlaybackParams(player.getPlaybackParams().setSpeed(speed));
        } catch (IllegalStateException | IllegalArgumentException ignored) {
            // Some decoders cannot change speed; the clip keeps playing at normal speed.
        }
    }

    /** Letterboxes the clip: TextureView would otherwise stretch it to the view's shape. */
    private void fit() {
        float w = getWidth(), h = getHeight();
        if (w == 0 || h == 0 || videoWidth == 0 || videoHeight == 0) return;
        float scale = Math.min(w / videoWidth, h / videoHeight);
        Matrix m = new Matrix();
        m.setScale(videoWidth * scale / w, videoHeight * scale / h, w / 2f, h / 2f);
        setTransform(m);
    }

    private void release() {
        prepared = false;
        if (player != null) {
            player.release();
            player = null;
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        fit();
    }

    @Override
    public void onSurfaceTextureAvailable(@NonNull SurfaceTexture st, int width, int height) {
        surface = new Surface(st);
        open();
    }

    @Override
    public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture st, int width, int height) {
        fit();
    }

    @Override
    public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture st) {
        release();
        if (surface != null) {
            surface.release();
            surface = null;
        }
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(@NonNull SurfaceTexture st) {
    }
}
