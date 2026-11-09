# PDFBox-Android: optional JPEG 2000 support is not included.
-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.**
# PDFBox loads its fonts and resources by name.
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
