{ stdenv }:
let sources = builtins.fromJSON (builtins.readFile ./canton-sources.json);
in
stdenv.mkDerivation rec {
  name = "daml2js";
  version = sources.version;
  src = builtins.fetchurl {
    url = "https://github.com/digital-asset/daml/releases/download/v${sources.daml_version}/daml-sdk-${sources.sdk_version}-${if stdenv.isDarwin then "macos" else "linux"}.tar.gz";
    sha256 = if stdenv.isDarwin then "120c3s7jpla3mynn9sdrdbmqg6y57rzy093avvq000xl0wr8a176" else "1jxxlsszzm0fsx2hfisjraf1axshp2jb7q476flj57frk0vgpzw0";
  };
  dontUnpack = true;
  installPhase = ''
    mkdir -p $out/bin
    tar --strip-components=1 -C $out -xzf $src sdk-${sources.sdk_version}/daml2js
    ln -s $out/daml2js/daml2js $out/bin/daml2js
  '';
}
