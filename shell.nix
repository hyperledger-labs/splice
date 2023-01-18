let
  inherit (pkgs) stdenv fetchzip;
  pkgs = import ./nix/default.nix {};
  # pyopenssl is currently broken on M1 due to
  # https://github.com/NixOS/nixpkgs/issues/174457#issuecomment-1137385758
  # To work around this we fetch some packages via rosetta.
  x86Pkgs = if builtins.currentSystem == "aarch64-darwin" then import ./nix/default.nix { system = "x86_64-darwin"; } else pkgs;

  daml_pbs = stdenv.mkDerivation rec {
    name = "daml-protobufs";
    sdk_version = "2.6.0-snapshot.20221212.11134.0.1ac41995";
    src = fetchzip {
      url = "https://github.com/digital-asset/daml/releases/download/v${sdk_version}/protobufs-${sdk_version}.zip";
      sha256="sha256-NHZYtvizwpWaRC7DDQ66bfZj0ak95mz8OLu7Ka06KZI=";
    };
    installPhase = ''
      mkdir -p $out/protos-${sdk_version}
      cp -r * $out/protos-${sdk_version}
    '';
  };

  # No macOS support for firefox
  linuxOnly = if stdenv.isDarwin then [ ] else with pkgs; [ envoy firefox ];
in pkgs.mkShell {
  SSL_CERT_FILE = "${pkgs.cacert}/etc/ssl/certs/ca-bundle.crt";
  packages = with pkgs; [

    # NOTE: please keep this list sorted for an easy overview and to avoid merge noise.

    ammonite
    buf
    cabal2nix
    canton
    circleci-cli
    curl
    daml_pbs
    docker
    evans
    geckodriver
    git
    (google-cloud-sdk.withExtraComponents ([google-cloud-sdk.components.gke-gcloud-auth-plugin ]))
    grpcurl
    haskellPackages.daml2ts
    hub # Github CLI for todo checker
    jq
    jsonnet
    kubectl
    lnav
    nix
    nodejs
    openapi-generator-cli
    openjdk11
    protobuf3_19
    protoc-gen-grpc-web
    postgresql_11
    ps
    python3
    python3Packages.sphinx_rtd_theme
    python3Packages.datadog
    ripgrep
    sbt
    scala
    selenium-server-standalone
    sphinx
    toxiproxy
    tmux
    unzip
    which
    x86Pkgs.sphinx-autobuild
    zip
  ] ++ linuxOnly;

  DAML_PROTOBUFS = "${daml_pbs}";
  SDK_VERSION = "${daml_pbs.sdk_version}";
}
