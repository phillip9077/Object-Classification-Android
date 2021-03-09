# Object-Classification-Android
An Android application using Google's CameraX and ML Kit API to run a custom .tflite object classification model.

The app is pretty straightforward, and no additional prep is needed to run it. All it does is use image input from the camera preview to allow the user to run a certain image-related .tflite model, 
whether that'd be image classification, text recognition, or more. The user can dictate his/her own .tflite model by adding it to the src/main/assets folder and writing the
name of the .tflite model in the setAssetFilePath method. The setAssetFilePath method is under the onCreate method and is called by the LocalModel builder.  
