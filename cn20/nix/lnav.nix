{ stdenv, fetchzip }:
# lnav breaks on newer pcre versions https://github.com/tstack/lnav/issues/638
# so we use the binary distribution that ships with a compatible pcre version.
stdenv.mkDerivation rec {
  name = "lnav";
  version = "0.11.2";

  src =
    if stdenv.isDarwin then
      fetchzip {
        url = "https://github.com/tstack/lnav/releases/download/v${version}/lnav-${version}-x86_64-macos.zip";
        sha256 = "sha256-8TY/N6aYyH+FGvwtLAOcEj6SbVQQty2s4X1GVDNE2vQ=";
      } else fetchzip {
        url = "https://github.com/tstack/lnav/releases/download/v${version}/lnav-${version}-x86_64-linux-musl.zip";
        sha256 = "sha256-QJbHYXkd8m7cJYW7fnNeGA4C9s7L3pCyJ1xZ1PjQWuM=";
      };

  installPhase = ''
    mkdir -p $out/bin
    cp lnav $out/bin/lnav
  '';
}
