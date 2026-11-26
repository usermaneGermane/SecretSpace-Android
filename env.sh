# The Android build environment of this repo, kept apart from anything else
# on the PC (E:\CLAUDE\SecretSpace-Mobile-Env\android). Source it in Git Bash.
ENV_DIR=/e/CLAUDE/SecretSpace-Mobile-Env/android
export JAVA_HOME="$(cygpath -w $ENV_DIR/jdk-21.0.13.1+2)"
export GRADLE_USER_HOME="$(cygpath -w $ENV_DIR/gradle-home)"
export ANDROID_HOME="$(cygpath -w $ENV_DIR/sdk)"
export PATH="$ENV_DIR/jdk-21.0.13.1+2/bin:$ENV_DIR/gradle-9.9.2/bin:$PATH"
# Emulators (headless) and their settings live in the env folder too.
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_USER_HOME="$(cygpath -w $ENV_DIR/android-user)"
export ANDROID_AVD_HOME="$(cygpath -w $ENV_DIR/android-user/avd)"
export PATH="$ENV_DIR/sdk/platform-tools:$ENV_DIR/sdk/emulator:$PATH"
# The release key (never in the repository): app/build.gradle.kts reads it.
export SECRETSPACE_SIGNING="$(cygpath -w $ENV_DIR/keys/release.properties)"
