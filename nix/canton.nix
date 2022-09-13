{ stdenv }:
stdenv.mkDerivation rec {
  name = "canton";
  version = "20220906";
  sdk_version = "2.4.0-snapshot.20220905.10544.0.a2ea3ce6";
  src = builtins.fetchTarball {
    url = "https://github.com/digital-asset/daml/releases/download/v${sdk_version}/canton-open-source-${version}.tar.gz";
    sha256 = "1ims3arm5zzhgmi7x229sq02cn2brb8x189rc238bz7mqlmr2yc5";
  };
  installPhase = ''
    mkdir - p $out
    cp -r * $out
  '';
}
