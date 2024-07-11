{ stdenv }:
let sources = builtins.fromJSON (builtins.readFile ./canton-sources.json);
in
stdenv.mkDerivation rec {
  name = "daml2js";
  version = sources.version;
  src = builtins.fetchurl {
    url = "https://digitalasset.jfrog.io/artifactory/assembly/daml/${sources.tooling_sdk_version}/daml-sdk-${sources.tooling_sdk_version}-${if stdenv.isDarwin then "macos" else "linux-intel"}.tar.gz";
    sha256 =
      if stdenv.isDarwin
        then "sha256:09ada05rykwmcsbk256h9m81819vfyhvxi5fm4q59f1nyfsk6cvh"
        else "sha256:0pzsgrrb1x9brpwmdpp7gz39j33rbagfgjl0ik3mggxq42kjfsdl";
  };
  dontUnpack = true;
  installPhase = ''
    mkdir -p $out/bin
    tar --strip-components=1 -C $out -xzf $src sdk-${sources.tooling_sdk_version}/daml2js
    ln -s $out/daml2js/daml2js $out/bin/daml2js
  '';
}
