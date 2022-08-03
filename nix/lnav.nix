{ stdenv, fetchzip }:
# lnav breaks on newer pcre versions https://github.com/tstack/lnav/issues/638
# so we use the binary distribution that ships with a compatible pcre version.
stdenv.mkDerivation rec {
  name = "lnav";
  version = "0.10.1";

  src =
    if stdenv.isDarwin then
      fetchzip {
        url = "https://github.com/tstack/lnav/releases/download/v${version}/lnav-${version}-os-x.zip";
        sha256 = "sha256-Y4++NFmbodxrew7xtoiAdWAFIHAfNeGKSpNq6jHj+4g=";
      } else fetchzip {
        url = "https://github.com/tstack/lnav/releases/download/v${version}/lnav-${version}-musl-64bit.zip";
        sha256 = "sha256-AeAX1gr3GYOc/dIhJwT9U4Jm7m71yKLzw1UA3j0kVPA=";
      };

  installPhase = ''
    mkdir -p $out/bin
    cp lnav $out/bin/lnav
  '';
}
