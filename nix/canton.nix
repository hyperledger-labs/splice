{ stdenv, use_enterprise }:
let sources = builtins.fromJSON (builtins.readFile ./canton-sources.json);
in
stdenv.mkDerivation rec {
  name = "canton";
  version = sources.version;
  src = if use_enterprise then
    builtins.fetchurl {
         url = "https://digitalasset.jfrog.io/artifactory/canton-enterprise/canton-enterprise-${sources.version}.tar.gz";
         sha256 = sources.enterprise_sha256;
    }
  else
    builtins.fetchurl {
        url = "https://www.canton.io/releases/canton-open-source-${sources.version}.tar.gz";
        sha256 = sources.oss_sha256;
    };
  dontUnpack = true;
  installPhase = ''
    mkdir -p $out
    tar --strip-components=1 -C $out -xzf $src
    # extract the first component of the path names
    tar -tzf $src | sed -ne '1s,/.*,,p' > $out/SUBDIR;
    ln -s $out/bin/canton $out/bin/canton-test
  '';
}
