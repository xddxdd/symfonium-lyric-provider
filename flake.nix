{
  description = "Symfonium LyricProvider Xposed module";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs = { nixpkgs, ... }:
    let
      system = "x86_64-linux";
      pkgs = import nixpkgs {
        inherit system;
        config = {
          allowUnfree = true;
          android_sdk.accept_license = true;
        };
      };
      android = pkgs.androidenv.composeAndroidPackages {
        platformVersions = [ "36" ];
        buildToolsVersions = [ "35.0.0" "36.0.0" ];
        includeEmulator = false;
        includeSystemImages = false;
        includeNDK = false;
      };
      androidSdk = android.androidsdk;
      androidHome = "${androidSdk}/libexec/android-sdk";
      androidTools = [
        pkgs.gradle
        pkgs.jdk17
        androidSdk
      ];
      gradleEnvironment = ''
        export ANDROID_HOME="${androidHome}"
        export ANDROID_SDK_ROOT="${androidHome}"
        export JAVA_HOME="${pkgs.jdk17.home}"
        export GRADLE_OPTS="-Dorg.gradle.project.android.aapt2FromMavenOverride=$ANDROID_HOME/build-tools/35.0.0/aapt2 ''${GRADLE_OPTS:-}"
      '';
      buildApk = name: task: pkgs.writeShellApplication {
        inherit name;
        runtimeInputs = androidTools;
        text = ''
          ${gradleEnvironment}
          exec gradle ${task} "$@"
        '';
      };
      buildDebugApk = buildApk "build-debug-apk" ":app:assembleDebug";
      buildReleaseApk = buildApk "build-release-apk" ":app:assembleRelease";
    in
    {
      devShells.${system}.default = pkgs.mkShell {
        packages = androidTools;

        ANDROID_HOME = androidHome;
        ANDROID_SDK_ROOT = androidHome;
        JAVA_HOME = "${pkgs.jdk17.home}";

        shellHook = gradleEnvironment;
      };

      apps.${system} = {
        "build-debug" = {
          type = "app";
          program = "${buildDebugApk}/bin/build-debug-apk";
        };

        "build-release" = {
          type = "app";
          program = "${buildReleaseApk}/bin/build-release-apk";
        };
      };
    };
}
