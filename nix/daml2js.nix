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
        then "sha256:1wl4hf4cjlnjgc22pw8r8m467clsid5lswkbma1x94hpyg4qhddf"
        else "sha256:0k2kfpr5sgcrl36d8drdfa72kpq1rw76j0fki8bhpny13pbsghj1";
  };
  dontUnpack = true;
  installPhase = ''
    mkdir -p $out/bin
    tar --strip-components=1 -C $out -xzf $src sdk-${sources.tooling_sdk_version}/daml2js
    ln -s $out/daml2js/daml2js $out/bin/daml2js
  '';
}
