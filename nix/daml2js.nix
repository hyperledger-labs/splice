{ stdenv }:
let sources = builtins.fromJSON (builtins.readFile ./canton-sources.json);
in
stdenv.mkDerivation rec {
  name = "daml2js";
  version = sources.version;
  src = builtins.fetchurl {
    url = "https://digitalasset.jfrog.io/artifactory/assembly/daml/${sources.sdk_version}/daml-sdk-${sources.sdk_version}-${if stdenv.isDarwin then "macos" else "linux"}.tar.gz";
    sha256 = if stdenv.isDarwin then "sha256:19izlc1pk4w8a7hqnqsq2w8s859zaqfyq3fjmlgb1vs4k09i40zz" else "sha256:1mdv2mpwisczs2pbf2pivi20kq8j9xzh52i3hr0b5042nz6h9zvq";
  };
  dontUnpack = true;
  installPhase = ''
    mkdir -p $out/bin
    tar --strip-components=1 -C $out -xzf $src sdk-${sources.sdk_version}/daml2js
    ln -s $out/daml2js/daml2js $out/bin/daml2js
  '';
}
