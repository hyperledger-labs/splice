{ pkgs, x86Pkgs }:
let
  inherit (pkgs) stdenv fetchzip;
  sources = builtins.fromJSON (builtins.readFile ./nix/canton-sources.json);

  # No macOS support for firefox
  linuxOnly = if stdenv.isDarwin then [ ] else with pkgs; [ envoy firefox iproute2 ];
in pkgs.mkShell {
  PULUMI_SKIP_UPDATE_CHECK = 1;
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
    jsonapi
    k9s
    kubectl
    kubernetes-helm
    lnav
    nix
    nodejs
    openapi-generator-cli
    openjdk11
    pigz
    postgresql_11
    pre-commit
    procps
    protobuf3_19
    protoc-gen-grpc-web
    ps
    pulumi-bin
    python3
    python3Packages.datadog
    python3Packages.sphinx_rtd_theme
    python3Packages.sphinx-copybutton
    ripgrep
    sbt
    scala
    selenium-server-standalone
    shellcheck
    sphinx
    tmux
    toxiproxy
    unzip
    which
    x86Pkgs.sphinx-autobuild
    zip
  ] ++ linuxOnly;

  DAML_PROTOBUFS = "${pkgs.daml_pbs}";
  CANTON = "${pkgs.canton}";
  SDK_VERSION = "${pkgs.daml_pbs.sdk_version}";
}
