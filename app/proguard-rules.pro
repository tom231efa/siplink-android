# The Linphone SDK ships its own consumer rules (proguard.txt inside the AAR) that keep every
# class referenced from JNI, so nothing extra is required for it here.

# Keep source file / line numbers so release stack traces stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
