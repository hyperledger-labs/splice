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
        then "sha256:16gn0kybfwj00dhgzmmvi6c87lzp8l91my1y8ia1sgi1isfqhrcj"
        else "sha256:1lwv3vbrlv00nd5ilhxdzx2vnlxymkcky403ny2bzahlz0qi3338";
  };
  dontUnpack = true;
  installPhase = ''
    mkdir -p $out/bin
    tar --strip-components=1 -C $out -xzf $src sdk-${sources.tooling_sdk_version}/daml2js
    ln -s $out/daml2js/daml2js $out/bin/daml2js
  '';
}
