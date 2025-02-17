{ pkgs, x86Pkgs, npmPkgs }:
let
  inherit (pkgs) stdenv fetchzip;

  # No macOS support for firefox
  linuxOnly = if stdenv.isDarwin then [ ] else with pkgs; [ iproute2 util-linux ];
in pkgs.mkShell {
  SSL_CERT_FILE = "${pkgs.cacert}/etc/ssl/certs/ca-bundle.crt";
  packages = with pkgs; [

    # NOTE: please keep this list sorted for an easy overview and to avoid merge noise.

    circleci-cli
    curl
    evans
    git
    github-cli
    (google-cloud-sdk.withExtraComponents ([google-cloud-sdk.components.gke-gcloud-auth-plugin ]))
    grpcurl
    helmfile
    jq
    k9s
    kubectl
    (pkgs.wrapHelm kubernetes-helm { plugins = [ kubernetes-helmPlugins.helm-diff ]; })
    lnav
    nix
    nodejs
    openjdk17
    pigz
    popeye
    postgresql_11
    procps
    ps
    (python3.withPackages(pkgs: [pkgs.flask pkgs.pyjwt]))
    ripgrep
    shellcheck
    tmux
    unzip
    which
    yq
    zip
  ] ++ linuxOnly;
}
