{ stdenv, fetchzip }:
# lnav breaks on newer pcre versions https://github.com/tstack/lnav/issues/638
# so we use the binary distribution that ships with a compatible pcre version.
stdenv.mkDerivation rec {
  name = "lnav";
  version = "0.11.1";

  src =
    if stdenv.isDarwin then
      fetchzip {
        url = "https://github.com/tstack/lnav/releases/download/v${version}/lnav-${version}-x86_64-macos.zip";
        sha256 = "sha256-pIIUGznYtEAPPPP1erX/LHB1IF5nlB311DPrSP4Nwrc=";
      } else fetchzip {
        url = "https://github.com/tstack/lnav/releases/download/v${version}/lnav-${version}-x86_64-linux-musl.zip";
        sha256 = "sha256-S8OzVOCsDLa2XJH6NskCGKVHe7QslZ6oainKIPHB5/c=";
      };

  installPhase = ''
    mkdir -p $out/bin
    cp lnav $out/bin/lnav
  '';
}
