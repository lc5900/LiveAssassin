package com.lc5900.liveassassin;

import android.Manifest;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.content.pm.PackageManager;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.hardware.usb.UsbDevice;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.util.Log;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.jiangdg.ausbc.widget.AspectRatioTextureView;
import com.google.common.util.concurrent.ListenableFuture;
import com.serenegiant.usb.Size;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.UVCCamera;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "LiveAssassin";
    private static final int CAMERA_PERMISSION_REQUEST = 2000;
    private static final int AUDIO_PERMISSION_REQUEST = 2001;
    private static final int[][] PREVIEW_CANDIDATES = new int[][]{
            {1920, 1080},
            {1280, 720},
            {720, 576},
            {720, 480},
            {640, 480}
    };

    private AspectRatioTextureView cameraView;
    private FrameLayout previewContainer;
    private PreviewView pipCameraView;
    private SwitchCompat pipSwitch;
    private View pipControlsPanel;
    private Button pipControlsToggleButton;
    private View rootContainer;
    private View controlPanel;
    private TextView statusView;

    private USBMonitor usbMonitor;
    private UVCCamera uvcCamera;
    private UsbAudioLoopback audioLoopback;

    private Button fullscreenButton;
    private boolean isFullscreenMode = false;
    private boolean chromeHidden = false;
    private UsbDevice currentDevice;
    private int currentPreviewWidth = 0;
    private int currentPreviewHeight = 0;
    private int pipSizePercent = 28;
    private int pipXPercent = 0;
    private int pipYPercent = 100;
    private ProcessCameraProvider pipCameraProvider;
    private boolean isPipEnabled = false;
    private boolean pendingCameraPermissionForUsbOpen = false;
    private boolean pendingCameraPermissionForPip = false;

    private final USBMonitor.OnDeviceConnectListener usbListener = new USBMonitor.OnDeviceConnectListener() {
        @Override
        public void onAttach(UsbDevice device) {
            Log.i(TAG, "onAttach: " + device);
            currentDevice = device;
            runOnUiThread(() -> statusView.setText(R.string.status_device_attached));
        }

        @Override
        public void onDetach(UsbDevice device) {
            Log.i(TAG, "onDetach: " + device);
            if (currentDevice != null && currentDevice.equals(device)) {
                currentDevice = null;
            }
            runOnUiThread(() -> {
                stopPreviewAndAudio();
                statusView.setText(R.string.status_waiting_device);
            });
        }

        @Override
        public void onConnect(UsbDevice device, USBMonitor.UsbControlBlock ctrlBlock, boolean createNew) {
            Log.i(TAG, "onConnect: " + device + ", createNew=" + createNew);
            currentDevice = device;
            openCameraDirect(ctrlBlock);
        }

        @Override
        public void onDisconnect(UsbDevice device, USBMonitor.UsbControlBlock ctrlBlock) {
            Log.i(TAG, "onDisconnect: " + device);
            runOnUiThread(() -> {
                stopPreviewAndAudio();
                statusView.setText(R.string.status_waiting_device);
            });
        }

        @Override
        public void onCancel(UsbDevice device) {
            Log.w(TAG, "onCancel permission: " + device);
            runOnUiThread(() -> Toast.makeText(
                    MainActivity.this, "USB 权限被拒绝", Toast.LENGTH_SHORT
            ).show());
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        cameraView = findViewById(R.id.camera_view);
        previewContainer = findViewById(R.id.preview_container);
        pipCameraView = findViewById(R.id.pip_camera_view);
        rootContainer = findViewById(R.id.root_container);
        controlPanel = findViewById(R.id.control_panel);
        statusView = findViewById(R.id.tv_status);
        Button startButton = findViewById(R.id.btn_start);
        Button stopButton = findViewById(R.id.btn_stop);
        fullscreenButton = findViewById(R.id.btn_fullscreen);
        pipSwitch = findViewById(R.id.switch_pip_front_camera);
        SeekBar pipSizeSeek = findViewById(R.id.seek_pip_size);
        SeekBar pipXSeek = findViewById(R.id.seek_pip_x);
        SeekBar pipYSeek = findViewById(R.id.seek_pip_y);
        pipControlsPanel = findViewById(R.id.pip_controls_panel);
        pipControlsToggleButton = findViewById(R.id.btn_toggle_pip_controls);

        cameraView.setAspectRatio(PREVIEW_CANDIDATES[0][0], PREVIEW_CANDIDATES[0][1]);
        pipCameraView.setScaleX(-1f);
        cameraView.setSurfaceTextureListener(new android.view.TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                if (uvcCamera != null && currentPreviewWidth > 0 && currentPreviewHeight > 0) {
                    try {
                        surface.setDefaultBufferSize(currentPreviewWidth, currentPreviewHeight);
                        uvcCamera.setPreviewTexture(surface);
                        uvcCamera.startPreview();
                        resetPreviewTransform();
                    } catch (Exception e) {
                        Log.w(TAG, "rebind preview texture failed", e);
                    }
                }
            }

            @Override
            public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
                resetPreviewTransform();
            }

            @Override
            public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
            }
        });
        audioLoopback = new UsbAudioLoopback(this);
        usbMonitor = new USBMonitor(this, usbListener);

        startButton.setOnClickListener(v -> requestOpenDevice());
        stopButton.setOnClickListener(v -> {
            stopPreviewAndAudio();
            statusView.setText(R.string.status_preview_stopped);
        });
        fullscreenButton.setOnClickListener(v -> toggleFullscreenRotate());
        pipSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> setPipEnabled(isChecked));
        pipControlsToggleButton.setOnClickListener(v -> togglePipControlsPanel());
        pipSizeSeek.setOnSeekBarChangeListener(new SimpleProgressListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                pipSizePercent = clamp(progress, 15, 60);
                updatePipLayout();
            }
        });
        pipXSeek.setOnSeekBarChangeListener(new SimpleProgressListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                pipXPercent = clamp(progress, 0, 100);
                updatePipLayout();
            }
        });
        pipYSeek.setOnSeekBarChangeListener(new SimpleProgressListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                pipYPercent = clamp(progress, 0, 100);
                updatePipLayout();
            }
        });
        View.OnClickListener previewTapListener = v -> {
            if (isFullscreenMode) {
                setChromeHidden(!chromeHidden);
            }
        };
        previewContainer.setOnClickListener(previewTapListener);
        cameraView.setOnClickListener(previewTapListener);
        pipCameraView.setOnClickListener(previewTapListener);
        previewContainer.post(this::updatePipLayout);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (usbMonitor != null) {
            if (!usbMonitor.isRegistered()) {
                usbMonitor.register();
            }
        }
        if (isPipEnabled) {
            startPipCamera();
        }
    }

    @Override
    protected void onStop() {
        if (!isChangingConfigurations()) {
            stopPreviewAndAudio();
            stopPipCamera();
            if (usbMonitor != null) {
                usbMonitor.unregister();
            }
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        stopPipCamera();
        if (usbMonitor != null) {
            usbMonitor.destroy();
            usbMonitor = null;
        }
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyAspectForCurrentOrientation();
        resetPreviewTransform();
        updatePipLayout();
        if (isFullscreenMode) {
            enterImmersiveMode();
        }
    }

    private void toggleFullscreenRotate() {
        isFullscreenMode = !isFullscreenMode;
        if (isFullscreenMode) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            enterImmersiveMode();
            setChromeHidden(true);
            fullscreenButton.setText(R.string.exit_fullscreen);
        } else {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            exitImmersiveMode();
            setChromeHidden(false);
            fullscreenButton.setText(R.string.enter_fullscreen);
        }
    }

    private void setChromeHidden(boolean hidden) {
        chromeHidden = hidden;
        controlPanel.setAlpha(hidden ? 0f : 1f);
        controlPanel.setVisibility(hidden ? View.GONE : View.VISIBLE);
        statusView.setVisibility(hidden ? View.GONE : View.VISIBLE);
        if (hidden) {
            enterImmersiveMode();
        } else {
            if (isFullscreenMode) {
                enterImmersiveMode();
            } else {
                exitImmersiveMode();
            }
        }
        resetPreviewTransform();
    }

    private void enterImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (controller != null) {
            controller.setSystemBarsBehavior(
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            );
            controller.hide(WindowInsetsCompat.Type.systemBars());
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            );
        }
    }

    private void exitImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), true);
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (controller != null) {
            controller.show(WindowInsetsCompat.Type.systemBars());
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }
    }

    private void requestOpenDevice() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            pendingCameraPermissionForUsbOpen = true;
            ActivityCompat.requestPermissions(
                    this, new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST
            );
            return;
        }
        if (usbMonitor == null) {
            return;
        }
        List<UsbDevice> devices = usbMonitor.getDeviceList();
        if (devices == null || devices.isEmpty()) {
            Toast.makeText(this, "未检测到 USB 采集卡", Toast.LENGTH_SHORT).show();
            statusView.setText(R.string.status_waiting_device);
            return;
        }
        UsbDevice target = currentDevice != null ? currentDevice : devices.get(0);
        currentDevice = target;
        usbMonitor.requestPermission(target);
    }

    private void openCameraDirect(USBMonitor.UsbControlBlock ctrlBlock) {
        stopPreviewAndAudio();
        try {
            uvcCamera = new UVCCamera();
            uvcCamera.open(ctrlBlock);
            Log.i(TAG, "supportedSizeRaw=" + uvcCamera.getSupportedSize());
            List<Size> mjpeg = uvcCamera.getSupportedSizeList(UVCCamera.FRAME_FORMAT_MJPEG);
            List<Size> yuyv = uvcCamera.getSupportedSizeList(UVCCamera.FRAME_FORMAT_YUYV);
            Log.i(TAG, "mjpegSizes=" + toSizeText(mjpeg));
            Log.i(TAG, "yuyvSizes=" + toSizeText(yuyv));

            int[] picked = chooseBestSize(mjpeg, yuyv);
            if (picked == null) {
                throw new IllegalStateException("no supported preview size");
            }
            int width = picked[0];
            int height = picked[1];
            int format = picked[2];
            currentPreviewWidth = width;
            currentPreviewHeight = height;

            applyAspectForCurrentOrientation();
            trySetPreviewSize(width, height, format);

            SurfaceTexture texture = cameraView.getSurfaceTexture();
            if (texture == null) {
                throw new IllegalStateException("camera view surface is null");
            }
            texture.setDefaultBufferSize(width, height);
            uvcCamera.setPreviewTexture(texture);
            uvcCamera.startPreview();
            runOnUiThread(this::resetPreviewTransform);

            runOnUiThread(() -> {
                statusView.setText(R.string.status_preview_running);
                startAudioIfPermitted();
                updatePipLayout();
            });
            Log.i(TAG, "preview opened size=" + width + "x" + height + ", format=" + format);
        } catch (Exception e) {
            Log.e(TAG, "openCameraDirect failed", e);
            runOnUiThread(() -> statusView.setText(R.string.status_camera_open_failed));
            stopPreviewAndAudio();
        }
    }

    private void trySetPreviewSize(int width, int height, int format) {
        try {
            uvcCamera.setPreviewSize(width, height, format);
            return;
        } catch (Exception ignored) {
        }
        try {
            uvcCamera.setPreviewSize(width, height, format, 0.5f);
            return;
        } catch (Exception ignored) {
        }
        uvcCamera.setPreviewSize(width, height, format, 1, 30, 0.5f);
    }

    private int[] chooseBestSize(List<Size> mjpeg, List<Size> yuyv) {
        List<Size> mjpegSafe = mjpeg != null ? mjpeg : new ArrayList<>();
        List<Size> yuyvSafe = yuyv != null ? yuyv : new ArrayList<>();
        for (int[] c : PREVIEW_CANDIDATES) {
            if (containsSize(mjpegSafe, c[0], c[1])) {
                return new int[]{c[0], c[1], UVCCamera.FRAME_FORMAT_MJPEG};
            }
        }
        for (int[] c : PREVIEW_CANDIDATES) {
            if (containsSize(yuyvSafe, c[0], c[1])) {
                return new int[]{c[0], c[1], UVCCamera.FRAME_FORMAT_YUYV};
            }
        }
        return null;
    }

    private boolean containsSize(List<Size> list, int width, int height) {
        for (Size s : list) {
            if (s.width == width && s.height == height) {
                return true;
            }
        }
        return false;
    }

    private String toSizeText(List<Size> sizes) {
        if (sizes == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < sizes.size(); i++) {
            Size s = sizes.get(i);
            sb.append(s.width).append("x").append(s.height);
            if (i < sizes.size() - 1) {
                sb.append(", ");
            }
        }
        sb.append("]");
        return sb.toString();
    }

    private void resetPreviewTransform() {
        if (currentPreviewWidth <= 0 || currentPreviewHeight <= 0) {
            return;
        }
        applyAspectForCurrentOrientation();
        int viewW = cameraView.getWidth();
        int viewH = cameraView.getHeight();
        if (viewW <= 0 || viewH <= 0) {
            return;
        }
        float sourceRatio = (float) currentPreviewWidth / (float) currentPreviewHeight;
        float viewRatio = (float) viewW / (float) viewH;
        float scaleX = 1f;
        float scaleY = 1f;
        if (viewRatio > sourceRatio) {
            scaleX = sourceRatio / viewRatio;
        } else {
            scaleY = viewRatio / sourceRatio;
        }
        Matrix matrix = new Matrix();
        matrix.setScale(scaleX, scaleY, viewW / 2f, viewH / 2f);
        cameraView.setTransform(matrix);
        cameraView.setTranslationX(0f);
        cameraView.setTranslationY(0f);
    }

    private void applyAspectForCurrentOrientation() {
        if (currentPreviewWidth <= 0 || currentPreviewHeight <= 0) {
            return;
        }
        // Keep source aspect ratio in both portrait and landscape to avoid stretching.
        cameraView.setAspectRatio(currentPreviewWidth, currentPreviewHeight);
    }

    private void setPipEnabled(boolean enabled) {
        isPipEnabled = enabled;
        if (!enabled && pipControlsPanel != null) {
            pipControlsPanel.setVisibility(View.GONE);
            if (pipControlsToggleButton != null) {
                pipControlsToggleButton.setText(R.string.pip_show_controls);
            }
        }
        if (!enabled) {
            stopPipCamera();
            pipCameraView.setVisibility(View.GONE);
            return;
        }
        startPipCamera();
    }

    private void togglePipControlsPanel() {
        if (pipControlsPanel == null || pipControlsToggleButton == null) {
            return;
        }
        if (!isPipEnabled) {
            Toast.makeText(this, "请先打开前置摄像头画中画", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean willShow = pipControlsPanel.getVisibility() != View.VISIBLE;
        pipControlsPanel.setVisibility(willShow ? View.VISIBLE : View.GONE);
        pipControlsToggleButton.setText(
                willShow ? R.string.pip_hide_controls : R.string.pip_show_controls
        );
    }

    private void startPipCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            pendingCameraPermissionForPip = true;
            ActivityCompat.requestPermissions(
                    this, new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST
            );
            return;
        }
        pipCameraView.setVisibility(View.VISIBLE);
        updatePipLayout();
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                pipCameraProvider = future.get();
                pipCameraProvider.unbindAll();
                Preview preview = new Preview.Builder().build();
                CameraSelector selector = new CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                        .build();
                preview.setSurfaceProvider(pipCameraView.getSurfaceProvider());
                pipCameraProvider.bindToLifecycle(this, selector, preview);
            } catch (Exception e) {
                Log.e(TAG, "startPipCamera failed", e);
                runOnUiThread(() -> {
                    pipCameraView.setVisibility(View.GONE);
                    if (pipSwitch != null) {
                        pipSwitch.setChecked(false);
                    }
                    Toast.makeText(this, "前置摄像头启动失败", Toast.LENGTH_SHORT).show();
                });
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void stopPipCamera() {
        if (pipCameraProvider != null) {
            try {
                pipCameraProvider.unbindAll();
            } catch (Exception ignored) {
            }
        }
    }

    private void updatePipLayout() {
        if (pipCameraView == null || previewContainer == null) {
            return;
        }
        int containerW = previewContainer.getWidth();
        int containerH = previewContainer.getHeight();
        if (containerW <= 0 || containerH <= 0) {
            previewContainer.post(this::updatePipLayout);
            return;
        }
        int pipW = Math.max(120, containerW * pipSizePercent / 100);
        int pipH = Math.max(68, pipW * 9 / 16);
        if (pipH > containerH) {
            pipH = containerH;
            pipW = Math.max(120, pipH * 16 / 9);
        }
        int left = (containerW - pipW) * pipXPercent / 100;
        int top = (containerH - pipH) * pipYPercent / 100;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) pipCameraView.getLayoutParams();
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.width = pipW;
        lp.height = pipH;
        lp.leftMargin = left;
        lp.topMargin = top;
        lp.setMarginStart(left);
        pipCameraView.setLayoutParams(lp);
        pipCameraView.bringToFront();
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void startAudioIfPermitted() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    this, new String[]{Manifest.permission.RECORD_AUDIO}, AUDIO_PERMISSION_REQUEST
            );
            return;
        }
        if (!audioLoopback.start()) {
            Toast.makeText(this, "音频输出启动失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void stopPreviewAndAudio() {
        audioLoopback.stop();
        if (uvcCamera != null) {
            try {
                uvcCamera.stopPreview();
            } catch (Exception ignored) {
            }
            try {
                uvcCamera.destroy();
            } catch (Exception ignored) {
            }
            uvcCamera = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            if (requestCode == AUDIO_PERMISSION_REQUEST) {
                statusView.setText(R.string.status_audio_permission_denied);
            } else {
                if (pendingCameraPermissionForPip && pipSwitch != null) {
                    pipSwitch.setChecked(false);
                }
                pendingCameraPermissionForUsbOpen = false;
                pendingCameraPermissionForPip = false;
                statusView.setText(R.string.status_camera_open_failed);
            }
            return;
        }
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            boolean shouldOpenUsb = pendingCameraPermissionForUsbOpen;
            boolean shouldStartPip = pendingCameraPermissionForPip || isPipEnabled;
            pendingCameraPermissionForUsbOpen = false;
            pendingCameraPermissionForPip = false;
            if (shouldOpenUsb) {
                requestOpenDevice();
            }
            if (shouldStartPip) {
                startPipCamera();
            }
        } else if (requestCode == AUDIO_PERMISSION_REQUEST) {
            startAudioIfPermitted();
        }
    }

    private abstract static class SimpleProgressListener implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
        }
    }
}
