{ pkgs ? import <nixpkgs> {} }:

let
  dpmVersion = "3.5.0-weekly-snapshot.20260409.59.0.v43aaa4c9";
  system = pkgs.stdenv.hostPlatform.system;
  os = if pkgs.stdenv.isDarwin then "darwin" else "linux";
  arch = if pkgs.stdenv.isAarch64 then "arm64" else "amd64";

  dpmHashes = {
    "x86_64-linux" = "sha256:1088zinqiaiz4krmcz7rpw66md4q7wdapshxvb183iggwi10mqia";
    "aarch64-linux" = "sha256:1dw1qxwpw4vcrh3crknpr3bvxvlzvl8lq4iy8nj440ksnfaskldq";
    "aarch64-darwin" = "sha256:0cg1jdic4nxcqlz5lg4jam2g4xnw12d7y8dwbgiwiz116j5x1kr2";
  };
  dpmHash = dpmHashes.${system} or (throw "Unsupported system: ${system}");
in
pkgs.stdenv.mkDerivation {
  pname = "dpm";
  version = dpmVersion;

  src = builtins.fetchurl {
    name = "dpm-sdk-${dpmVersion}.tar.gz";
    url = "https://get.digitalasset.com/unstable/install/dpm-sdk/dpm-${dpmVersion}-${os}-${arch}.tar.gz";
    sha256 = dpmHash;
  };
  nativeBuildInputs = [ pkgs.yq-go ];
  dontUnpack = true;
  installPhase = ''
    mkdir -p $out
    tar --strip-components=1 -C $out -xzf $src
    chmod -R u+w $out
    # We need just these components from the SDK, to save space
    yq --inplace \
      '.spec.components |= with_entries(
        select(   .key == "damlc"
               or .key == "daml-script"
               or .key == "canton-enterprise"
               )
              )' \
      $out/sdk-manifest.yaml
    DPM_HOME=$out $out/bin/dpm bootstrap $out

    rm -rf $out/oci-registry
    rm -rf $out/cache/oci-layout/*

    mkdir -p $out/nix-support
    echo export DPM_HOME=$out > $out/nix-support/setup-hook
    echo export DPM_SDK_VERSION=${dpmVersion} >> $out/nix-support/setup-hook
  '';
}
