# OkHttp ships with optional Conscrypt/BouncyCastle/OpenJSSE hooks it only uses when those
# providers are on the classpath. They are not, so R8 only needs to be told to stop warning.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
