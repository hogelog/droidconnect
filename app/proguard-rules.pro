# Keep original class/member names so crash stack traces stay readable without
# uploading mapping.txt. R8 still performs shrinking and optimization.
-dontobfuscate

# sshlib (com.trilead.ssh2 fork) registers cipher / KEX / signature / userauth
# implementations through reflection and string lookups. Without these keeps,
# R8 strips classes that are only reached after KEX completes (notably the
# userauth phase), so the client closes the connection silently right after
# `KEX done` — no exception is thrown, just nothing happens.
-keep class com.trilead.ssh2.** { *; }
-keepclassmembers class com.trilead.ssh2.** { *; }
-dontwarn com.trilead.ssh2.**
