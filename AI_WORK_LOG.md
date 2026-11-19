# AI work log

## 2026-11-19

- Local and remote `main` matched at `1bf5175`. Leave machine-specific Android Studio deployment and device metadata local.
- Phone package `com.example.delayedsecrect` was installed with version code 1.
- Excluded encrypted secret and timer preferences from cloud backup and device transfer. The local encryption keys are device-bound, and restored ciphertext can prevent the stores from opening. Ordinary settings remain eligible for backup.
- Verify a new install and a restore on an expendable device or emulator before testing against stored secrets.
- The debug build and existing JVM unit tests passed with a full Kotlin compilation. Initial incremental builds had unresolved symbols.
- Installed version code 1 in place. The installed hash matched the verified build. The launcher reached the Android permission prompt and the process stayed running.
