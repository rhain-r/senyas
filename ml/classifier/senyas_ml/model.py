"""The sign classifier: a small 1-D convolutional network over landmark sequences.

Only built-in TensorFlow Lite operations are used (no "Select TF ops"), so the exported model runs
with the plain LiteRT runtime on Android and stays small (well under 1 MB).
"""
import keras
from keras import layers

from .features import FEATURES, SEQUENCE_LENGTH


def build_model(num_classes, width=96):
    inp = keras.Input(shape=(SEQUENCE_LENGTH, FEATURES), name="landmarks")
    x = inp
    for filters, kernel in ((width, 5), (width, 5)):
        x = layers.Conv1D(filters, kernel, padding="same", use_bias=False)(x)
        x = layers.BatchNormalization()(x)
        x = layers.Activation("relu")(x)
    x = layers.MaxPooling1D(2)(x)
    for filters, kernel in ((width * 4 // 3, 3), (width * 4 // 3, 3)):
        x = layers.Conv1D(filters, kernel, padding="same", use_bias=False)(x)
        x = layers.BatchNormalization()(x)
        x = layers.Activation("relu")(x)
    x = layers.Concatenate()([layers.GlobalAveragePooling1D()(x), layers.GlobalMaxPooling1D()(x)])
    x = layers.Dropout(0.4)(x)
    x = layers.Dense(128, activation="relu")(x)
    x = layers.Dropout(0.3)(x)
    out = layers.Dense(num_classes, activation="softmax", name="probabilities")(x)
    return keras.Model(inp, out, name="senyas_fsl")
