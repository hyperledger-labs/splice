{ pkgs ? import <nixpkgs> {} }:

let
  sources = builtins.fromJSON (builtins.readFile ./dpm-sources.json);
  dpmPath = "europe-docker.pkg.dev/da-images/public/components/dpm";
  dpmVersion = sources.version;
  dpmRef = "${dpmPath}:${dpmVersion}";

  dpmHash = sources.${pkgs.stdenv.hostPlatform.system} or (throw "Unsupported system: $pkgs.stdenv.hostPlatform.system}");

  ociPlatforms = {
    "x86_64-linux" = "linux/amd64";
    "aarch64-linux" = "linux/arm64";
    "x86_64-darwin" = "darwin/amd64";
    "aarch64-darwin" = "darwin/arm64";
  };
  ociPlatform = ociPlatforms.${pkgs.stdenv.hostPlatform.system} or (throw "Unsupported system: ${pkgs.stdenv.hostPlatform.system}");
in
pkgs.stdenv.mkDerivation {
  pname = "dpm";
  version = dpmVersion;

  src = pkgs.stdenv.mkDerivation {
    name = "dpm-pull-${dpmRef}.${ociPlatform}";

    nativeBuildInputs = [ pkgs.oras pkgs.cacert ];

    buildCommand = ''
      set -e
      echo "Pulling dpm component from ${dpmRef}.${ociPlatform}"
      oras pull --platform ${ociPlatform} -o $out ${dpmRef}
    '';
    outputHashMode = "recursive";
    outputHashAlgo = "sha256";
    outputHash = dpmHash;
  };

  installPhase = ''
    mkdir -p $out/bin
    install -Dm755 $src/dpm $out/bin/dpm
  '';
}
