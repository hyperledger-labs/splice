{ stdenv }:
stdenv.mkDerivation rec {
  name = "canton";
  version = "20221213";
  sdk_version = "2.6.0-snapshot.20221212.11134.0.1ac41995";
  src = builtins.fetchTarball {
    url = "https://github.com/digital-asset/daml/releases/download/v${sdk_version}/canton-open-source-${version}.tar.gz";
    sha256 = "1z2fgvvjhjaj5jar8x0qhv6kckv50sg9fjqp8qn1n7z0kqrlm234";
  };
  installPhase = ''
    mkdir - p $out
    cp -r * $out
  '';
}
