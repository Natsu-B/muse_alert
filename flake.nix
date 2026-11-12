{
  description = "muse-alert dev shell";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs = { nixpkgs, ... }:
    let
      pkgs = nixpkgs.legacyPackages.x86_64-linux;
    in {
      # テスト用メールサーバー (tools/fakemail) を動かす環境。
      devShells.x86_64-linux.fakemail = pkgs.mkShell {
        packages = [ pkgs.dovecot (pkgs.python3.withPackages (p: [ p.aiosmtpd ])) ];
      };

      # Android Emulator (Google 配布のバイナリ) を nix-ld で動かすためのライブラリ。
      devShells.x86_64-linux.emulator = pkgs.mkShell {
        NIX_LD_LIBRARY_PATH = pkgs.lib.makeLibraryPath (with pkgs; [
          stdenv.cc.cc.lib zlib libpulseaudio libGL libdrm libxkbcommon
          nss nspr expat fontconfig freetype dbus alsa-lib libuuid
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
