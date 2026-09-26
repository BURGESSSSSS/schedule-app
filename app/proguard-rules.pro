# libadb's bundled Conscrypt adapter references platform classes that are not
# present on newer Android SDK boot class paths. They are optional compatibility
# branches and are not needed by the supported runtime.
-dontwarn com.android.org.conscrypt.SSLParametersImpl
-dontwarn org.apache.harmony.xnet.provider.jsse.SSLParametersImpl
