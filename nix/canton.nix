{ stdenv }:
let sources = builtins.fromJSON (builtins.readFile ./canton-sources.json);
in
stdenv.mkDerivation rec {
  name = "canton";
  version = sources.version;
  src = builtins.fetchTarball {
    url = "https://digitalasset.jfrog.io/artifactory/canton-research/snapshot/canton-research-${sources.version}.tar.gz";
    sha256 = sources.sha256;
  };
  installPhase = ''
    mkdir - p $out
    cp -r * $out
  '';
}
