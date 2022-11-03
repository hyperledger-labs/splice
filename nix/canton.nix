{ stdenv }:
stdenv.mkDerivation rec {
  name = "canton";
  version = "20221101";
  sdk_version = "2.5.0-snapshot.20221028.10865.0.1b726fe8";
  src = builtins.fetchTarball {
    url = "https://github.com/digital-asset/daml/releases/download/v${sdk_version}/canton-open-source-${version}.tar.gz";
    sha256 = "0v4inyh3pi7286y9mpxdfa08nk2dg9nmfbjw0f9a7ys4p7kjskph";
  };
  installPhase = ''
    mkdir - p $out
    cp -r * $out
  '';
}
