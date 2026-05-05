# Keep original class/member names so crash stack traces stay readable without
# uploading mapping.txt. R8 still performs shrinking and optimization.
-dontobfuscate
