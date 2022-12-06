{ stdenv }:
stdenv.mkDerivation rec {
  name = "canton";
  version = "20221122";
  sdk_version = "2.5.0-snapshot.20221120.10983.0.218a6a8a";
  src = builtins.fetchTarball {
    url = "https://github.com/digital-asset/daml/releases/download/v${sdk_version}/canton-open-source-${version}.tar.gz";
    sha256 = "15j0rqgw4xfn00117kzsly8pjqyzxmy661g64bfq16v12snr5x91";
  };
  installPhase = ''
    mkdir - p $out
    cp -r * $out
  '';
}
