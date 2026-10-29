# MediaPipe Face Landmarker model asset

The app expects the official Face Landmarker task bundle at:

`app/src/main/assets/face_landmarker.task`

Download the model from Google's MediaPipe model storage:

https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task

Save the downloaded file with the exact name `face_landmarker.task` in this directory.
Do not paste the URL into the app or download the model at runtime: inference must remain on-device.

The task bundle is not committed by this change because it is a binary model asset. The provider
fails fast with a descriptive error if the asset is missing. Model licensing and attribution terms
should be checked before distributing an app that bundles it.
