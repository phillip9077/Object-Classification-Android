package com.example.camerax;

import android.annotation.SuppressLint;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.media.Image;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
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

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

public class MainActivity extends AppCompatActivity {

    private PreviewView previewView;
    private ObjectDetector objectDetector;
    private final int REQUEST_CODE_PERMISSIONS = 101;
    private final String[] REQUIRED_PERMISSIONS = new String[]{"android.permission.CAMERA"};
    private final static String TAG = "Anything unique";
    private Executor executor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
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

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Checks the orientation of the screen
        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            Toast.makeText(this, "landscape", Toast.LENGTH_SHORT).show();
        } else if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT) {
            Toast.makeText(this, "portrait", Toast.LENGTH_SHORT).show();
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
                .setTargetResolution(new Size(1280, 720))
                .build();
        // Connect the preview use case to the previewView
        preview.setSurfaceProvider(previewView.getSurfaceProvider());
        // Choose the camera by requiring a lens facing
        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build();

        // Creates an ImageAnalysis for analyzing the camera preview feed
        ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                .setTargetResolution(new Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();

        // This part is only needed if the device you're working on is capable of rotating between
        // portrait and landscape mode. In the VAB-950 this is not possible, so this is not
        // necessary to make the app work.
//        OrientationEventListener mOrientationEventListener =
//                new OrientationEventListener(this) {
//                    @Override
//                    public void onOrientationChanged(int orientation) {
//                        int rotation;
//
//                        // Monitors orientation values to determine the target rotation value
//                        if (orientation >= 45 && orientation < 135) {
//                            rotation = Surface.ROTATION_270;
//                        } else if (orientation >= 135 && orientation < 225) {
//                            rotation = Surface.ROTATION_180;
//                        } else if (orientation >= 225 && orientation < 315) {
//                            rotation = Surface.ROTATION_90;
//                        } else {
//                            rotation = Surface.ROTATION_0;
//                        }
//                        // Updates target rotation value to {@link ImageAnalysis}
//                        imageAnalysis.setTargetRotation(rotation);
//                    }
//                };
//        mOrientationEventListener.enable();

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

        // Attach use cases to our lifecycle owner, the app itself
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
                        String text = "";
                        float confidence = 0;
                        for (DetectedObject detectedObject : results) {
                            for (DetectedObject.Label label : detectedObject.getLabels()) {
                                text = label.getText();
                                confidence = label.getConfidence();
                            }
                        }
                        TextView textView = findViewById(R.id.resultText);
                        TextView confText = findViewById(R.id.confidence);
                        if (!text.equals("")) {
                            textView.setText(text);
                            confText.setText(String.format("Confidence = %f", confidence));
                        } else {
                            textView.setText("Detecting");
                            confText.setText("?");
                        }
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