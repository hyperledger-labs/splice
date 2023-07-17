{ stdenv }:
let sources = builtins.fromJSON (builtins.readFile ./canton-sources.json);
in
stdenv.mkDerivation rec {
  name = "daml2js";
  version = sources.version;
  src = builtins.fetchurl {
    url = "https://github.com/digital-asset/daml/releases/download/v${sources.daml_version}/daml-sdk-${sources.daml_version}-${if stdenv.isDarwin then "macos" else "linux"}.tar.gz";
    sha256 = if stdenv.isDarwin then "0k6y1ry9057y6lg73pybqasydb5b63mnzf4c33450nrfqhv3ai76" else "1knp3bb9ma83cg95z9g7nnvvwy6h26x24qr8fscqg69jv961wdg1";
  };
  dontUnpack = true;
  installPhase = ''
    mkdir -p $out/bin
    tar --strip-components=1 -C $out -xzf $src sdk-${sources.daml_version}/daml2js
    ln -s $out/daml2js/daml2js $out/bin/daml2js
  '';
}
