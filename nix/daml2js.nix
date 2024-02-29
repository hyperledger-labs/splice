{ stdenv }:
let sources = builtins.fromJSON (builtins.readFile ./canton-sources.json);
in
stdenv.mkDerivation rec {
  name = "daml2js";
  version = sources.version;
  src = builtins.fetchurl {
    url = "https://digitalasset.jfrog.io/artifactory/assembly/daml/${sources.sdk_version}/daml-sdk-${sources.sdk_version}-${if stdenv.isDarwin then "macos" else "linux"}.tar.gz";
    sha256 =
      if stdenv.isDarwin
      then "sha256:1qznslp47idvp3yw2vzbrl0vx161gbb6i4ljnskdl5vf47dqkj62"
      else "sha256:1msr2bq8kp7hrbdfyv6vk9gbh7ypmqfsc0sgssc3b4v3arpr2ya1";
  };
  dontUnpack = true;
  installPhase = ''
    mkdir -p $out/bin
    tar --strip-components=1 -C $out -xzf $src sdk-${sources.sdk_version}/daml2js
    ln -s $out/daml2js/daml2js $out/bin/daml2js
  '';
}
