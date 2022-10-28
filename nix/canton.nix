{ stdenv }:
stdenv.mkDerivation rec {
  name = "canton";
  version = "20221025";
  sdk_version = "2.5.0-snapshot.20221024.10827.0.c8adc54a";
  src = builtins.fetchTarball {
    url = "https://github.com/digital-asset/daml/releases/download/v${sdk_version}/canton-open-source-${version}.tar.gz";
    sha256 = "1rk57hli86r3krj0b6gb7485fqs9sjza4xlhsfd1vnwz21chq6yg";
  };
  installPhase = ''
    mkdir - p $out
    cp -r * $out
  '';
}
