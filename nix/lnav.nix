{ stdenv, fetchzip }:
# lnav breaks on newer pcre versions https://github.com/tstack/lnav/issues/638
# so we use the binary distribution that ships with a compatible pcre version.
stdenv.mkDerivation rec {
  name = "lnav";
  version = "0.14.0";

  src =
    if stdenv.isDarwin then
      fetchzip {
        url = "https://github.com/tstack/lnav/releases/download/v${version}/lnav-${version}-x86_64-macos.zip";
        sha256 = "sha256:f282d567e1bdb2c3e54d9502671b8011c0d4e3700b2616cf72778602f8ee0371";
      } else fetchzip {
        url = "https://github.com/tstack/lnav/releases/download/v${version}/lnav-${version}-linux-musl-x86_64.zip";
        sha256 = "sha256-k8x83y64GIpGMnNyYLGpo5bRFvaxENhwbKSdWw4UCuU=";
      };

  installPhase = ''
    mkdir -p $out/bin
    cp lnav $out/bin/lnav
  '';
}
