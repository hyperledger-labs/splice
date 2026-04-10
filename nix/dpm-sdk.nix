{ pkgs ? import <nixpkgs> {} }:

let
  sources = builtins.fromJSON (builtins.readFile ./dpm-sdk-sources.json);
  system = pkgs.stdenv.hostPlatform.system;
  os = if pkgs.stdenv.isDarwin then "darwin" else "linux";
  arch = if pkgs.stdenv.isAarch64 then "arm64" else "amd64";
  dpmHash = sources.${system} or (throw "Unsupported system: ${system}");
in
pkgs.stdenv.mkDerivation {
  pname = "dpm-sdk";
  version = sources.version;

  src = builtins.fetchurl {
    name = "dpm-sdk-${sources.version}.tar.gz";
    url = "https://get.digitalasset.com/unstable/install/dpm-sdk/dpm-${sources.version}-${os}-${arch}.tar.gz";
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
    echo export DPM_SDK_VERSION=${sources.version} >> $out/nix-support/setup-hook
  '';
}
