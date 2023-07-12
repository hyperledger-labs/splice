{ lib, stdenv, makeWrapper, fetchurl, jre }:
let sources = builtins.fromJSON (builtins.readFile ./canton-sources.json);
in
stdenv.mkDerivation rec {
  pname = "json-api";
  version = sources.daml_version;

  src = fetchurl {
    url = "https://github.com/digital-asset/daml/releases/download/v${version}/http-json-${version}.jar";
    sha256 = "sha256-k4z8IVNLymp1YVmroFwhYqh1N2AM3lp5eppUUHA4uLw=";
  };

  nativeBuildInputs = [ makeWrapper ];

  buildCommand = ''
    jar=$out/share/java/json-api_${version}.jar
    install -Dm444 $src $jar
    makeWrapper ${jre}/bin/java $out/bin/json-api --add-flags "-jar $jar"
  '';

  meta = with lib; {
    description = "HTTP JSON API";
    longDescription = ''
      The Daml HTTP JSON API service
    '';
    homepage = "https://docs.daml.com/json-api/index.html";
    sourceProvenance = with sourceTypes; [ binaryBytecode ];
    license = licenses.asl20;
    platforms = platforms.all;
  };
}
