{ stdenv }:
stdenv.mkDerivation rec {
  name = "canton";
  version = "20221011";
  sdk_version = "2.5.0-snapshot.20221010.10736.0.2f453a14";
  src = builtins.fetchTarball {
    url = "https://github.com/digital-asset/daml/releases/download/v${sdk_version}/canton-open-source-${version}.tar.gz";
    sha256 = "12jr3w44s3p7n4q59js5gp5l6n1mxkiz542h49a6sxf3wcdigq79";
  };
  installPhase = ''
    mkdir - p $out
    cp -r * $out
  '';
}
