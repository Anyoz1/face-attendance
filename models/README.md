# Модели

Источник: [OpenCV Zoo](https://github.com/opencv/opencv_zoo),
commit `48b10fa505aeb51fbd127fc60a5c660040e823b7`.

| Файл | Оригинал | SHA-256 |
|---|---|---|
| yunet.onnx | face_detection_yunet_2023mar.onnx | 8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4 |
| sface.onnx | face_recognition_sface_2021dec.onnx | 0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79 |

Юридические условия берутся у поставщика моделей, отдельно от кода приложения:
[YuNet](https://github.com/opencv/opencv_zoo/tree/48b10fa505aeb51fbd127fc60a5c660040e823b7/models/face_detection_yunet),
[SFace](https://github.com/opencv/opencv_zoo/tree/48b10fa505aeb51fbd127fc60a5c660040e823b7/models/face_recognition_sface).
Официальный пример: https://docs.opencv.org/4.x/d0/dd4/tutorial_dnn_face.html.

Загрузка: `uv run --cache-dir /tmp/face-id-uv-cache python scripts/download_models.py`.
Весов и персональных данных в Git нет.
