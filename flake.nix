{
  description = "muse-alert dev shell";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs = { nixpkgs, ... }:
    let
      pkgs = nixpkgs.legacyPackages.x86_64-linux;
    in {
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
