{ stdenv }:
let sources = builtins.fromJSON (builtins.readFile ./canton-sources.json);
in
stdenv.mkDerivation rec {
  name = "daml2js";
  version = sources.version;
  src = builtins.fetchurl {
    url = "https://github.com/digital-asset/daml/releases/download/v${sources.daml_version}/daml-sdk-${sources.daml_version}-${if stdenv.isDarwin then "macos" else "linux"}.tar.gz";
    sha256 = if stdenv.isDarwin then "18xx508pqqhcdgrvc5az3xprg329xff5pv78jzrv5dmd4d5m54cc" else "1inwzfa904m5g0gmvykv5cq1m4qmrv89x85h8svlh058hhv1cc4i";
  };
  dontUnpack = true;
  installPhase = ''
    mkdir -p $out/bin
    tar --strip-components=1 -C $out -xzf $src sdk-${sources.daml_version}/daml2js
    ln -s $out/daml2js/daml2js $out/bin/daml2js
  '';
}
