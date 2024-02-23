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
      then "sha256:1292rz7amv74595jcmympmh75lhn2v8qlbzlapv4aqmrddcm4q6p"
      else "sha256:00i7q2lk25icfd693rdmf6k7dyh65z5fi5cd8cal53l99kl3q7f8";
  };
  dontUnpack = true;
  installPhase = ''
    mkdir -p $out/bin
    tar --strip-components=1 -C $out -xzf $src sdk-${sources.sdk_version}/daml2js
    ln -s $out/daml2js/daml2js $out/bin/daml2js
  '';
}
