{ stdenv, fetchzip }:
# lnav breaks on newer pcre versions https://github.com/tstack/lnav/issues/638
# so we use the binary distribution that ships with a compatible pcre version.
stdenv.mkDerivation rec {
  name = "lnav";
  version = "0.12.2";

  src =
    if stdenv.isDarwin then
      fetchzip {
        url = "https://github.com/tstack/lnav/releases/download/v${version}/lnav-${version}-x86_64-macos.zip";
        sha256 = "sha256-3JH7Kflbr6weH7t1YOYqFy7cncZyx+vWntDQ1Ovpd8w=";
      } else fetchzip {
        url = "https://github.com/tstack/lnav/releases/download/v${version}/lnav-${version}-linux-musl-x86_64.zip";
        sha256 = "sha256-Zahqcior1oWAhDaK1K3u/o6AsLpKuiQxNmGtRb8AEj4=";
      };

  installPhase = ''
    mkdir -p $out/bin
    cp lnav $out/bin/lnav
  '';
}
