let
  pkgs = import ./nix/default.nix {};
  # pyopenssl is currently broken on M1 due to
  # https://github.com/NixOS/nixpkgs/issues/174457#issuecomment-1137385758
  # To work around this we fetch some packages via rosetta.
  x86Pkgs = if builtins.currentSystem == "aarch64-darwin" then import ./nix/default.nix { system = "x86_64-darwin"; } else pkgs;
in pkgs.mkShell {
  SSL_CERT_FILE = "${pkgs.cacert}/etc/ssl/certs/ca-bundle.crt";
  buildInputs = with pkgs; [

    # NOTE: please keep this list sorted for an easy overview and to avoid merge noise.

    ammonite
    buf
    cabal2nix
    canton
    circleci-cli
    curl
    docker
    (lib.optional stdenv.isLinux envoy)
    git
    google-cloud-sdk
    grpcurl
    haskellPackages.daml2ts
    hub # Github CLI for todo checker
    jq
    jsonnet
    kubectl
    lnav
    nix
    nodejs
    openjdk11
    protobuf
    protoc-gen-grpc-web
    postgresql_11
    python3
    python3Packages.sphinx_rtd_theme
    sbt
    sphinx
    tmux
    unzip
    x86Pkgs.sphinx-autobuild
    zip
  ];
}
