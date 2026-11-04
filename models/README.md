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

## Найденная модель ArcFace

`models/arcface.onnx` скопирована из локального проекта Orange Pi. Через SSH
проверена Orange Pi 5B: файлы в проектах `pi-face-recognition-advanced`,
`pi-face-recognition-prod` и резервной копии имеют ту же SHA-256.

Это **InsightFace `w600k_r50.onnx`: ArcFace / ResNet50@WebFace600K**, используемая
в наборах buffalo_l и buffalo_m. Размер: 174 383 860 байт (166.31 MiB).
Контрольная сумма:

```text
4c06341c33c2ca1f86781dab0e829f88ad5b64be9fba56e56bc9ebdefc619e43
```

Вход RGB `N×3×112×112`, нормализация `(pixel - 127.5) / 127.5`, выход — 512
чисел с последующей L2-нормализацией. ONNX экспортирован PyTorch 1.9,
содержит 43 590 976 параметров. Загрузка и вычисление через OpenCV DNN проверены.
На двух публичных тестовых изображениях прогретый проход самой нейросети занял
около 73 мс на этом ноутбуке; это проверка совместимости, не оценка точности группы.

Идентификация подтверждена совпадением SHA-256 с `R50_SHA256` в
[загрузчике PhotoPrism](https://github.com/photoprism/photoprism/blob/develop/scripts/dist/download-arcface.sh).
Архитектура и условия использования описаны в
[InsightFace Model Zoo](https://github.com/deepinsight/insightface/blob/master/python-package/docs/model_zoo.md).
Веса не отправляются в Git; описание файла сохранено в [arcface.json](arcface.json).

На этом этапе файл доступен для подключения, активной моделью приложения
остаётся SFace. Шаблоны SFace (128 чисел) нельзя использовать с ArcFace (512).
Переход требует отдельного подключения модели и повторной съёмки людей;
подходящие пороги нужно измерить для ArcFace отдельно.
