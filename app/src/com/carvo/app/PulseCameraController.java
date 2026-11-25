package com.carvo.app;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;

import java.util.Arrays;

public class PulseCameraController {

    public interface Listener {
        void onWaveSample(float waveVal, boolean fingerOn, float progress01,
                          String status, int liveBpm);
        void onComplete(PulseEngine.PulseResult result);
        void onError(String message);
    }

    private static final long SETTLE_MS = 1500L;
    private static final long MEASURE_MS = 15000L;

    private final Context ctx;
    private final Listener listener;
    private final PulseEngine engine = new PulseEngine();
    private final Handler mainHand = new Handler(Looper.getMainLooper());

    private HandlerThread bgThread;
    private Handler bgHand;
    private CameraDevice cam;
    private CameraCaptureSession session;
    private ImageReader reader;
    private Surface previewSurface;
    private CaptureRequest.Builder reqBuilder;

    private volatile boolean running = false;
    private boolean aeLockSupported = false;
    private boolean awbLockSupported = false;
    private boolean aeLocked = false;
    private Range<Integer> bestFpsRange = null;

    private long measureMs = MEASURE_MS;
    private long fingerSinceMs = 0L;
    private long lastFingerMs = 0L;
    private long measureStartMs = 0L;
    private boolean measuring = false;
    private int frameCounter = 0;
    private int lastLiveBpm = 0;

    public PulseCameraController(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
    }

    public boolean isRunning() {
        return running;
    }

    public void start(final TextureView tv) {
        start(tv, MEASURE_MS);
    }

    public void start(final TextureView tv, long durationMs) {
        if (running) return;
        measureMs = Math.max(8000L, Math.min(35000L, durationMs));
        running = true;
        engine.reset();
        fingerSinceMs = 0L;
        lastFingerMs = 0L;
        measureStartMs = 0L;
        measuring = false;
        aeLocked = false;
        frameCounter = 0;
        lastLiveBpm = 0;

        bgThread = new HandlerThread("CorvoPulseCam");
        bgThread.start();
        bgHand = new Handler(bgThread.getLooper());

        if (tv.isAvailable()) {
            openCamera(tv.getSurfaceTexture());
        } else {
            tv.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                    if (running && cam == null) openCamera(st);
                }
                @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {}
                @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                    stop();
                    return true;
                }
                @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {}
            });
        }
    }

    public synchronized void stop() {
        running = false;
        try {
            if (session != null) {
                try { session.stopRepeating(); } catch (Exception ignored) {}
                session.close();
                session = null;
            }
        } catch (Exception ignored) {}
        try {
            if (cam != null) {
                cam.close();
                cam = null;
            }
        } catch (Exception ignored) {}
        try {
            if (reader != null) {
                reader.close();
                reader = null;
            }
        } catch (Exception ignored) {}
        try {
            if (previewSurface != null) {
                previewSurface.release();
                previewSurface = null;
            }
        } catch (Exception ignored) {}
        if (bgThread != null) {
            bgThread.quitSafely();
            bgThread = null;
            bgHand = null;
        }
    }

    private void openCamera(final SurfaceTexture st) {
        try {
            CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            if (cm == null) {
                fail("Camera unavailable");
                return;
            }
            String chosenId = null;
            CameraCharacteristics chosenCc = null;
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics cc = cm.getCameraCharacteristics(id);
                Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    Boolean hasFlash = cc.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                    if (chosenId == null || (hasFlash != null && hasFlash)) {
                        chosenId = id;
                        chosenCc = cc;
                        if (hasFlash != null && hasFlash) break;
                    }
                }
            }
            if (chosenId == null || chosenCc == null) {
                fail("Rear camera not found");
                return;
            }

            Boolean aeLock = chosenCc.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE);
            Boolean awbLock = chosenCc.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE);
            aeLockSupported = aeLock != null && aeLock;
            awbLockSupported = awbLock != null && awbLock;

            Range<Integer>[] fpsList = chosenCc.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            bestFpsRange = null;
            if (fpsList != null) {
                for (Range<Integer> r : fpsList) {
                    if (r.getUpper() == 30 && (bestFpsRange == null || r.getLower() > bestFpsRange.getLower())) {
                        bestFpsRange = r;
                    }
                }
            }

            Size yuvSize = new Size(320, 240);
            StreamConfigurationMap map = chosenCc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map != null) {
                Size[] sizes = map.getOutputSizes(ImageFormat.YUV_420_888);
                if (sizes != null) {
                    Size best = null;
                    for (Size s : sizes) {
                        if (s.getWidth() >= 160 && s.getHeight() >= 120 && s.getWidth() <= 640) {
                            if (best == null || (s.getWidth() * s.getHeight() < best.getWidth() * best.getHeight())) {
                                best = s;
                            }
                        }
                    }
                    if (best != null) yuvSize = best;
                }
            }

            st.setDefaultBufferSize(yuvSize.getWidth(), yuvSize.getHeight());
            previewSurface = new Surface(st);
            reader = ImageReader.newInstance(yuvSize.getWidth(), yuvSize.getHeight(), ImageFormat.YUV_420_888, 2);
            reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader r) {
                    Image img = null;
                    try {
                        img = r.acquireLatestImage();
                        if (img != null && running) {
                            processFrame(img);
                        }
                    } catch (Exception ignored) {
                    } finally {
                        if (img != null) img.close();
                    }
                }
            }, bgHand);

            cm.openCamera(chosenId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    if (!running) {
                        camera.close();
                        return;
                    }
                    cam = camera;
                    startSession();
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    camera.close();
                    fail("Camera disconnected");
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    camera.close();
                    fail("Camera busy — close other camera apps and retry");
                }
            }, bgHand);
        } catch (SecurityException se) {
            fail("Camera permission needed");
        } catch (CameraAccessException ce) {
            fail("Unable to open rear camera");
        } catch (Exception e) {
            fail("Camera startup failed");
        }
    }

    private void startSession() {
        try {
            if (cam == null || previewSurface == null || reader == null) return;
            reqBuilder = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            reqBuilder.addTarget(previewSurface);
            reqBuilder.addTarget(reader.getSurface());
            reqBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            reqBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            reqBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH);
            if (bestFpsRange != null) {
                reqBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, bestFpsRange);
            }

            cam.createCaptureSession(Arrays.asList(previewSurface, reader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession s) {
                            if (!running || cam == null) return;
                            session = s;
                            try {
                                session.setRepeatingRequest(reqBuilder.build(), null, bgHand);
                            } catch (Exception e) {
                                fail("Camera preview failed");
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession s) {
                            fail("Camera stream setup failed");
                        }
                    }, bgHand);
        } catch (Exception e) {
            fail("Camera session failed");
        }
    }

    private void lockExposureIfSupported() {
        if (aeLocked || session == null || reqBuilder == null) return;
        aeLocked = true;
        try {
            if (aeLockSupported) reqBuilder.set(CaptureRequest.CONTROL_AE_LOCK, true);
            if (awbLockSupported) reqBuilder.set(CaptureRequest.CONTROL_AWB_LOCK, true);
            session.setRepeatingRequest(reqBuilder.build(), null, bgHand);
        } catch (Exception ignored) {
        }
    }

    private void unlockExposure() {
        aeLocked = false;
        if (session == null || reqBuilder == null) return;
        try {
            if (aeLockSupported) reqBuilder.set(CaptureRequest.CONTROL_AE_LOCK, false);
            if (awbLockSupported) reqBuilder.set(CaptureRequest.CONTROL_AWB_LOCK, false);
            session.setRepeatingRequest(reqBuilder.build(), null, bgHand);
        } catch (Exception ignored) {
        }
    }

    private void processFrame(Image img) {
        long now = SystemClock.elapsedRealtime();
        PulseEngine.FrameStats fs = PulseEngine.extractYuvCenter(img);

        if (!fs.fingerOn) {
            // Brief tolerance (450ms) for micro shift; longer drop resets measurement cleanly
            if (lastFingerMs > 0 && (now - lastFingerMs) > 450L) {
                fingerSinceMs = 0L;
                measureStartMs = 0L;
                measuring = false;
                if (aeLocked) unlockExposure();
                engine.reset();
                lastLiveBpm = 0;
            }
            notifyUi(0f, false, 0f, "COVER REAR CAMERA + FLASH LIGHTLY", 0);
            return;
        }

        lastFingerMs = now;
        if (fingerSinceMs == 0L) fingerSinceMs = now;

        long onDur = now - fingerSinceMs;
        float wave = engine.pushSample(now, fs.avgR);

        if (!measuring) {
            if (onDur < SETTLE_MS) {
                notifyUi(wave, true, 0f, "HOLD STILL \u00B7 STABILIZING LIGHT...", 0);
                return;
            }
            measuring = true;
            measureStartMs = now;
            lockExposureIfSupported();
        }

        long el = now - measureStartMs;
        float prog = Math.min(1f, el / (float) measureMs);
        frameCounter++;
        if (frameCounter % 24 == 0 && engine.getCount() >= 110) {
            int est = engine.estimateLiveBpm();
            if (est > 0) lastLiveBpm = est;
        }

        int secLeft = Math.max(0, (int) Math.ceil((measureMs - el) / 1000.0));
        String status = lastLiveBpm > 0
                ? "LOCKING PULSE \u00B7 " + secLeft + "S"
                : "READING WAVE \u00B7 " + secLeft + "S";
        notifyUi(wave, true, prog, status, lastLiveBpm);

        if (el >= measureMs) {
            final PulseEngine.PulseResult res = engine.finalizeReading();
            stop();
            mainHand.post(new Runnable() {
                @Override
                public void run() {
                    if (listener != null) listener.onComplete(res);
                }
            });
        }
    }

    private void notifyUi(final float wave, final boolean on, final float prog,
                          final String status, final int liveBpm) {
        mainHand.post(new Runnable() {
            @Override
            public void run() {
                if (running && listener != null) {
                    listener.onWaveSample(wave, on, prog, status, liveBpm);
                }
            }
        });
    }

    private void fail(final String msg) {
        stop();
        mainHand.post(new Runnable() {
            @Override
            public void run() {
                if (listener != null) listener.onError(msg);
            }
        });
    }
}
