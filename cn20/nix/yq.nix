# Nix contains 'yq' versions 3 and below. However, we require version 4 and above for the template.
# Note - Cannot use 'fetchTarball' to fetch 'yq' as the generated archives do not contain a single
#        top level directory. See - https://github.com/NixOS/nix/issues/7083.

{ stdenv }:
let
  version = "4.40.5";
  platform = if stdenv.isDarwin then "yq_darwin_amd64" else "yq_linux_amd64";
  hash =
    if stdenv.isDarwin then
      "12l6jygkrylxx2gvbx3cv7wyylpkb4krmvl9q8bc1nk8w6nm0b2y"
    else
      "1yi6bkd3mlld9hpa6j8azdzp8qsqza5nni32jvn5rshp2z7gbjxw";
  tarball =
    builtins.fetchurl {
      url = "https://github.com/mikefarah/yq/releases/download/v${version}/${platform}.tar.gz";
      sha256 = "${hash}";
    };
in
  stdenv.mkDerivation {
    name = "yq";
    version = "$version";
    src = tarball;
    sourceRoot = ".";
    buildPhase = "tar xvf $src";
    installPhase = ''
      mkdir -p $out/bin
      cp ${platform} $out/bin/yq
    '';
  }
