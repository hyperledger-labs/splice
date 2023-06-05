{ stdenv, fetchzip }:
let sources = builtins.fromJSON (builtins.readFile ./canton-sources.json);
in stdenv.mkDerivation rec {
  name = "daml-protobufs";
  sdk_version = sources.daml_version;
  src = fetchzip {
    url = "https://github.com/digital-asset/daml/releases/download/v${sdk_version}/protobufs-${sdk_version}.zip";
    sha256 = "sha256-iNGQCVjnm5PUR5N8/K1nrfTPI7jkWUpswQ2pb2UZI2c=";
  };
  installPhase = ''
    mkdir -p $out/protos-${sdk_version}
    cp -r * $out/protos-${sdk_version}
  '';
}
