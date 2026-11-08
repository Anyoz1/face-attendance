# Error Prone's compile-time annotation refers to a javac-only enum; Android
# does not provide javax.lang.model. It is not used by the device test runtime.
-dontwarn javax.lang.model.element.Modifier
