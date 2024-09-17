{ stdenv, fetchzip }:
# lnav breaks on newer pcre versions https://github.com/tstack/lnav/issues/638
# so we use the binary distribution that ships with a compatible pcre version.
stdenv.mkDerivation rec {
  name = "lnav";
  version = "0.12.0";

  src =
    if stdenv.isDarwin then
      fetchzip {
        url = "https://github.com/tstack/lnav/releases/download/v${version}/lnav-${version}-x86_64-macos.zip";
        sha256 = "sha256-3+mpfqSu1KigrBrnVZ1s9/9da4tce7xQUgWcmdH9//k=";
      } else fetchzip {
        url = "https://github.com/tstack/lnav/releases/download/v${version}/lnav-${version}-linux-musl-x86_64.zip";
        sha256 = "sha256-dr1nkOCOyzLq6eP/s2lK2UvpWNe7WIo6v4QF+riVtEo=";
      };

  installPhase = ''
    mkdir -p $out/bin
    cp lnav $out/bin/lnav
  '';
}
