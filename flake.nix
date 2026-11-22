{
  description = "muse-alert dev shell";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs = { nixpkgs, ... }:
    let
      pkgs = nixpkgs.legacyPackages.x86_64-linux;
      # Only the Android Emulator compatibility shell needs this Expat override.
      # 2.10.0 fixes CVE-2026-113351/115506/117610/118370; upstream patch
      # libexpat/libexpat#1492 additionally fixes CVE-2026-124317.
      secureExpat = pkgs.expat.overrideAttrs (old: {
        version = "2.10.0";
        src = pkgs.fetchurl {
          url = "https://github.com/libexpat/libexpat/releases/download/R_2_10_0/expat-2.10.0.tar.xz";
          hash = "sha256-xiS6KZj8Zd8RY6SWQf8/mrcxOLEWQdkshr7g+wGxvH8=";
        };
        patches = (old.patches or [ ]) ++ [
          ./patches/expat-cve-2026-124317.patch
          ./patches/expat-cve-2026-134703.patch
        ];
      });
    in {
      # テスト用メールサーバー (tools/fakemail) を動かす環境。
      devShells.x86_64-linux.fakemail = pkgs.mkShell {
        packages = [ pkgs.dovecot (pkgs.python3.withPackages (p: [ p.aiosmtpd ])) ];
      };

      # Android Emulator (Google 配布のバイナリ) を nix-ld で動かすためのライブラリ。
      devShells.x86_64-linux.emulator = pkgs.mkShell {
        NIX_LD_LIBRARY_PATH = pkgs.lib.makeLibraryPath (with pkgs; [
          stdenv.cc.cc.lib zlib libpulseaudio libGL libdrm libxkbcommon
          nss nspr secureExpat fontconfig freetype dbus alsa-lib libuuid
          libpng libx11 libxcb libxext libxfixes libxrender libxdamage libxcomposite
          libxcursor libxi libxrandr libxtst libxkbfile
          libxcb-wm libxcb-image libxcb-keysyms libxcb-render-util
        ]);
      };

      devShells.x86_64-linux.default = pkgs.mkShell {
        packages = [ pkgs.jdk21 ];
        shellHook = ''
          export JAVA_HOME=${pkgs.jdk21}/lib/openjdk
          export ANDROID_HOME=$PWD/.android-sdk
          export PATH=$ANDROID_HOME/platform-tools:$PATH
        '';
      };
    };
}
