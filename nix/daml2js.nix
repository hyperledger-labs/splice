{ stdenv, use_enterprise }:
let sources = builtins.fromJSON (builtins.readFile ./canton-sources.json);
in
stdenv.mkDerivation rec {
  name = "daml2js";
  version = sources.version;
  src = if use_enterprise then
      builtins.fetchurl {
        url = "https://digitalasset.jfrog.io/artifactory/assembly/daml/${sources.tooling_sdk_version}/daml-sdk-${sources.tooling_sdk_version}-${if stdenv.isDarwin then "macos-x86_64" else "linux-intel"}.tar.gz";
        sha256 =
          if stdenv.isDarwin
            then "sha256:1pj24m49h31ngybmv5f40w638gja3vsgbqddwr6r0iclbpf8b89n"
            else "sha256:0ga2cvc9z4zv5yyz3lra9s5zf6pxbj8dc3hbm4921720xkap6164";
      }
    else
      builtins.fetchurl {
        url = "https://github.com/digital-asset/daml/releases/download/${sources.daml_release}/daml-sdk-${sources.tooling_sdk_version}-${if stdenv.isDarwin then "macos-x86_64" else "linux-x86_64"}.tar.gz";
        sha256 =
          if stdenv.isDarwin
            then "sha256:1jzy0n669brs7x37jfayiiqz692chgi8n95a22dr9vfh8dj8ac6j"
            else "sha256:0d1a9gxxvlrypyl8z4jb68xi19rp6ic9kgizlccqjfl0wnqmyxfz";
      };
  dontUnpack = true;
  installPhase = ''
    mkdir -p $out/bin
    tar --strip-components=1 -C $out -xzf $src sdk-${sources.tooling_sdk_version}/daml2js
    ln -s $out/daml2js/daml2js $out/bin/daml2js
  '';
}
