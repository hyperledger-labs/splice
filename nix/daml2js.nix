{ stdenv }:
let sources = builtins.fromJSON (builtins.readFile ./canton-sources.json);
in
stdenv.mkDerivation rec {
  name = "daml2js";
  version = sources.version;
  src = builtins.fetchurl {
    url = "https://github.com/digital-asset/daml/releases/download/v${sources.daml_version}/daml-sdk-${sources.daml_version}-${if stdenv.isDarwin then "macos" else "linux"}.tar.gz";
    sha256 = if stdenv.isDarwin then "09w4k96y9nxlj7mb0n9r5ks7qc9xffz6snn9r0p00pzzgqaxypzp" else "0mn54fzhy05q4haq4z4lj2d40chxks1y5pq5f8jaccn6afd4lycj";
  };
  dontUnpack = true;
  installPhase = ''
    mkdir -p $out/bin
    tar --strip-components=1 -C $out -xzf $src sdk-${sources.daml_version}/daml2js
    ln -s $out/daml2js/daml2js $out/bin/daml2js
  '';
}
