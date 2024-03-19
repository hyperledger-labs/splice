{ stdenv }:
let sources = builtins.fromJSON (builtins.readFile ./canton-sources.json);
in
stdenv.mkDerivation rec {
  name = "daml2js";
  version = sources.version;
  src = builtins.fetchurl {
    url = "https://digitalasset.jfrog.io/artifactory/assembly/daml/${sources.sdk_version}/daml-sdk-${sources.sdk_version}-${if stdenv.isDarwin then "macos" else "linux-intel"}.tar.gz";
    sha256 =
      if stdenv.isDarwin
      then "sha256:03gwza1761cxy6g3ns530fa72nfg1ir23pk5xy6wnrs59qm676jh"
      else "sha256:0i2j2qrdsz9kw6cdb2pv24yfjsh39m9h28agjr67z1ap648haxb7";
  };
  dontUnpack = true;
  installPhase = ''
    mkdir -p $out/bin
    tar --strip-components=1 -C $out -xzf $src sdk-${sources.sdk_version}/daml2js
    ln -s $out/daml2js/daml2js $out/bin/daml2js
  '';
}
