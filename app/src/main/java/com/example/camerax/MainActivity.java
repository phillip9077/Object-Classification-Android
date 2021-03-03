    package com.example.camerax;

import android.annotation.SuppressLint;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.media.Image;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.AspectRatio;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.common.model.LocalModel;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.objects.DetectedObject;
import com.google.mlkit.vision.objects.ObjectDetection;
import com.google.mlkit.vision.objects.ObjectDetector;
import com.google.mlkit.vision.objects.custom.CustomObjectDetectorOptions;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

public class MainActivity extends AppCompatActivity {

    private PreviewView previewView;
    private ObjectDetector objectDetector;
    private final int REQUEST_CODE_PERMISSIONS = 101;
    private final String[] REQUIRED_PERMISSIONS = new String[]{"android.permission.CAMERA"};
    private final String TAG = "Anything unique ";
    private Executor executor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getSupportActionBar().hide();

        previewView = findViewById(R.id.previewView);
        executor = ContextCompat.getMainExecutor(this);

        if (allPermissionsGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this,
                    REQUIRED_PERMISSIONS,
                    REQUEST_CODE_PERMISSIONS);
        }

        // Loads a LocalModel from a custom .tflite file
        LocalModel localModel = new LocalModel.Builder()
                .setAssetFilePath("efficientnet_lite0_int8_2.tflite")
                .build();

        CustomObjectDetectorOptions customObjectDetectorOptions =
                new CustomObjectDetectorOptions.Builder(localModel)
                        .setDetectorMode(CustomObjectDetectorOptions.STREAM_MODE)
                        .enableClassification()
                        .setClassificationConfidenceThreshold(0.5f)
                        .setMaxPerObjectLabelCount(3)
                        .build();
        objectDetector = ObjectDetection.getClient(customObjectDetectorOptions);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (allPermissionsGranted()) {
            startCamera();
        } else {
            Toast.makeText(this, "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    /**
     * Starts the camera preview
     */
    public void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            ProcessCameraProvider cameraProvider;
            // Camera provider is now guaranteed to be available
            try {
                cameraProvider = cameraProviderFuture.get();
                bindPreviewAndAnalyzer(cameraProvider);
            } catch (ExecutionException | InterruptedException e) {
                e.printStackTrace();
            }
        }, executor);
    }

    /**
     * Creates camera preview and image analyzer to bind to the app's lifecycle.
     *
     * @param cameraProvider a @NonNull camera provider object
     */
    private void bindPreviewAndAnalyzer(@NonNull ProcessCameraProvider cameraProvider) {
        // Set up the view finder use case to display camera preview
        Preview preview = new Preview.Builder()
                .setTargetResolution(new Size(1920, 1080))
                .build();
        // Connect the preview use case to the previewView
        preview.setSurfaceProvider(previewView.getSurfaceProvider());
        // Choose the camera by requiring a lens facing
        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build();

        // Creates an ImageAnalysis for analyzing the camera preview feed
        ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                .setTargetResolution(new Size(1920, 1080))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();
        imageAnalysis.setAnalyzer(executor,
                new ImageAnalysis.Analyzer() {
                    @Override
                    public void analyze(@NonNull ImageProxy imageProxy) {
                        @SuppressLint("UnsafeExperimentalUsageError") Image mediaImage =
                                imageProxy.getImage();
                        if (mediaImage != null) {
                            processImage(mediaImage, imageProxy)
                                    .addOnCompleteListener(new OnCompleteListener<List<DetectedObject>>() {
                                        @Override
                                        public void onComplete(@NonNull Task<List<DetectedObject>> task) {
                                            imageProxy.close();
                                        }
                                    });
                        }
                    }
                });

        // Unbind all previous use cases before binding new ones
        cameraProvider.unbindAll();

        // Attach use cases to the camera with the same lifecycle owner
        cameraProvider.bindToLifecycle(this,
                cameraSelector,
                preview,
                imageAnalysis);
    }

    /**
     * Throws an InputImage into the ML Kit ObjectDetector for processing
     *
     * @param mediaImage the Image image converted from the ImageProxy image
     * @param imageProxy the ImageProxy image from the camera preview
     */
    private Task<List<DetectedObject>> processImage(Image mediaImage, ImageProxy imageProxy) {
        InputImage image =
                InputImage.fromMediaImage(mediaImage,
                        imageProxy.getImageInfo().getRotationDegrees());
        return objectDetector.process(image)
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception e) {
                        String error = "Failed to process. Error: " + e.getMessage();
                        Log.e(TAG, error);
                    }
                })
                .addOnSuccessListener(new OnSuccessListener<List<DetectedObject>>() {
                    @Override
                    public void onSuccess(List<DetectedObject> results) {
                        String success = "Object(s) detected successfully!";
                        String text = "";
                        int index = 0;
                        float confidence = 0;
                        for (DetectedObject detectedObject : results) {
                            Rect boundingBox = detectedObject.getBoundingBox();
                            Integer trackingId = detectedObject.getTrackingId();
                            for (DetectedObject.Label label : detectedObject.getLabels()) {
                                text = label.getText();
                                index = label.getIndex();
                                confidence = label.getConfidence();
                            }
                        }
                        Log.i(TAG, success + "; " + "Object detected: " + text + "; "
                                + "Confidence: " + confidence);
                    }
                });
    }

    /**
     * Checks if all the permissions in the required permission array are already granted.
     *
     * @return Return true if all the permissions defined are already granted
     */
    private boolean allPermissionsGranted() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(getApplicationContext(), permission) !=
                    PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }
}