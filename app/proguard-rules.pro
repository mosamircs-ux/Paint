-keepclassmembers class * implements android.os.Parcelable {
    static ** CREATOR;
}

-keep class com.google.android.material.** { *; }

# Google Mobile Ads SDK
-keep class com.google.android.gms.ads.** { *; }
-dontwarn com.google.android.gms.ads.**
