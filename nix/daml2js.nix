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
      then "sha256:18faq0k9jxwf1j22pqjv341ydv89yl6fwp3a7vqb1fwf7pjx4k8c"
      else "sha256:113jb03i6f3xli3l9wykxhrcf7ag0s5hl24kvdpry8wv996h8kcm";
  };
  dontUnpack = true;
  installPhase = ''
    mkdir -p $out/bin
    tar --strip-components=1 -C $out -xzf $src sdk-${sources.sdk_version}/daml2js
    ln -s $out/daml2js/daml2js $out/bin/daml2js
  '';
}
