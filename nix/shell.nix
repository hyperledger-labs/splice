{ pkgs, x86Pkgs, npmPkgs }:
let
  inherit (pkgs) stdenv fetchzip;
  sources = builtins.fromJSON (builtins.readFile ./canton-sources.json);
  cometbftDriverSources = builtins.fromJSON (builtins.readFile ./cometbft-driver-sources.json);
  damlCompilerSources = builtins.fromJSON (builtins.readFile ./daml-compiler-sources.json);

  # No macOS support for firefox
  linuxOnly = if stdenv.isDarwin then [ ] else with pkgs; [ firefox iproute2 util-linux ];

in pkgs.mkShell {
  PULUMI_SKIP_UPDATE_CHECK = 1;
  SSL_CERT_FILE = "${pkgs.cacert}/etc/ssl/certs/ca-bundle.crt";
  packages = with pkgs; [

    # NOTE: please keep this list sorted for an easy overview and to avoid merge noise.
    istioctl
    ammonite
    auth0-cli
    cabal2nix
    canton
    circleci-cli
    curl
    docker
    evans
    geckodriver
    getopt
    gh
    git
    (google-cloud-sdk.withExtraComponents ([google-cloud-sdk.components.gke-gcloud-auth-plugin ]))
    grpcurl
    daml2js
    hub # Github CLI for todo checker
    jq
    jsonnet
    k6
    k9s
    kubectl
    kubernetes-helm
    lnav
    nix
    nodejs
    nodePackages.node2nix
    npmPkgs.syncpack
    openapi-generator-cli
    openjdk17
    pigz
    popeye
    postgresql_14
    pre-commit
    procps
    protobuf
    ps
    pulumi-bin
    python3
    python3Packages.aiohttp
    python3Packages.colorlog
    (python3Packages.datadog.overrideAttrs (old: {
                                             doCheck = false;
                                             doInstallCheck = false;
                                     }))
    python3Packages.GitPython
    python3Packages.marshmallow-dataclass
    python3Packages.polib
    python3Packages.pyjwt
    python3Packages.pyyaml
    python3Packages.regex
    python3Packages.requests
    python3Packages.sphinx_rtd_theme
    python3Packages.sphinx-copybutton
    git-search-replace
    python3.pkgs.sphinx-reredirects
    ripgrep
    rsync
    sbt
    scala_2_13
    selenium-server-standalone
    shellcheck
    sphinx
    sphinx-lint
    tmux
    toxiproxy
    unzip
    which
    x86Pkgs.sphinx-autobuild
    zip

    # Package required to install daml studio
    yq-go
  ] ++ linuxOnly;

  CANTON = "${pkgs.canton}";
  DAML_COMPILER_VERSION = "${damlCompilerSources.version}";
  SDK_VERSION = "${sources.tooling_sdk_version}";
  COMETBFT_RELEASE_VERSION = "${cometbftDriverSources.version}";
  COMETBFT_DRIVER = "${pkgs.cometbft_driver}";
  PULUMI_HOME = "${pkgs.pulumi-bin}";
  # Avoid sbt-assembly falling over. See https://github.com/sbt/sbt-assembly/issues/496
  LC_ALL = if stdenv.isDarwin then "" else "C.UTF-8";
  # Avoid "warning: setlocale: LC_ALL: cannot change locale (C.UTF-8)"
  # warnings in damlc.
  LOCALE_ARCHIVE_2_27 = if pkgs.stdenv.hostPlatform.libc == "glibc"
                        then "${pkgs.glibcLocales}/lib/locale/locale-archive"
                        else null;

  PROTOC = "${pkgs.protobuf}";

  PULUMI_VERSION="${pkgs.pulumi-bin.version}";

}
