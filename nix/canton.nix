{ stdenv }:
stdenv.mkDerivation rec {
  name = "canton";
  version = "20230106";
  src = builtins.fetchTarball {
    url = "https://digitalasset.jfrog.io/artifactory/canton-research/snapshot/canton-research-${version}.tar.gz";
    sha256 = "1a7pxhgs00d867c45kf18wa9lcjzcl20q7amxsq54hiza0bfsl5m";
  };
  installPhase = ''
    mkdir - p $out
    cp -r * $out
    sed -i 's|-cp \\"''${PROG_HOME}/lib/canton-research-2.6.0-SNAPSHOT.jar\\"|-cp \\"''${CLASSPATH}:''${PROG_HOME}/lib/canton-research-2.6.0-SNAPSHOT.jar\\"|' \
      $out/bin/canton
  '';
}
