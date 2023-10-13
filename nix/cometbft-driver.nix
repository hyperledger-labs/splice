{ stdenv }:

let sources = builtins.fromJSON (builtins.readFile ./cometbft-driver-sources.json);
in
stdenv.mkDerivation rec {
  name = "cometbft-driver";
  version = sources.version;
  src = builtins.fetchurl {
    url = "https://digitalasset.jfrog.io/artifactory/canton-drivers/com/digitalasset/canton/drivers/canton-drivers/${sources.version}/canton-drivers-${sources.version}.tar.gz";
    sha256 = sources.sha256;
  };
  dontUnpack = true;
  installPhase = ''
    mkdir -p $out
    tar -xzf $src
    cp canton-drivers-${sources.version}/lib/canton-drivers-${sources.version}-all.jar $out/driver.jar
  '';
}
