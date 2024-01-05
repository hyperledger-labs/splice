{ stdenv }:
let sources = builtins.fromJSON (builtins.readFile ./canton-sources.json);
in
stdenv.mkDerivation rec {
  name = "daml2js";
  version = sources.version;
  src = builtins.fetchurl {
    url = "https://digitalasset.jfrog.io/artifactory/assembly/daml/${sources.sdk_version}/daml-sdk-${sources.sdk_version}-${if stdenv.isDarwin then "macos" else "linux"}.tar.gz";
    sha256 = if stdenv.isDarwin then "sha256:0ifhcdqv2q05asbzwklxagy3n4gwcz0abg4ac5bca9k1zjb80p4j" else "sha256:0biw9qq5pw2arfzkpklhi2pzqmbpvyv8mi41z46ghj7lyy9i55dk";
  };
  dontUnpack = true;
  installPhase = ''
    mkdir -p $out/bin
    tar --strip-components=1 -C $out -xzf $src sdk-${sources.sdk_version}/daml2js
    ln -s $out/daml2js/daml2js $out/bin/daml2js
  '';
}
