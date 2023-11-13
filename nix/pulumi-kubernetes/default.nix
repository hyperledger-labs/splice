{ stdenv, buildGoModule, fetchFromGitHub, go }:

let
  k8s-cli-runtime = stdenv.mkDerivation {
    name = "k8s-cli-runtime";
    src = fetchFromGitHub {
      owner = "kubernetes";
      repo = "cli-runtime";
      rev = "v0.28.2";
      hash = "sha256-XJJ0AmxTnjwB0juURwOekWKnrYun/CyaUiA5c6j1oig=";
    };
    patches = [
      ./fix-config-groupversion-override.diff
    ];
    buildPhase = ''
      mkdir $out
      cp -r --reflink=auto . $out/
    '';
  };

  # If this version is updated the version in `overlays.nix` used for `pulumi plugin install` must be updated as well
  version = "4.5.4";
  vendorHash = "sha256-OwyQCYVzgILfZCB0F1Ljq1A5os2eEcRuH7laaAM5bug=";

  pulumi-kubernetes-src = stdenv.mkDerivation {
    name = "pulumi-kubernetes-src";
    src = fetchFromGitHub {
      owner = "pulumi";
      repo = "pulumi-kubernetes";
      rev = "v${version}";
      hash = "sha256-9sRk6qnlRatp2UT90TVOe1alnak5SFtLYi399fJP54A=";
    };
    nativeBuildInputs = [ go ];
    buildPhase = ''
      mkdir $out/
      cd provider
      go mod edit -replace k8s.io/cli-runtime=./k8s-cli-runtime
      cp -r --reflink=auto ${k8s-cli-runtime} k8s-cli-runtime
      cp -r --reflink=auto . $out/
    '';
  };

  pulumi-gen-kubernetes = buildGoModule {
    inherit version vendorHash;

    pname = "pulumi-gen-kubernetes";
    src = pulumi-kubernetes-src;

    CGO_ENABLED = 0;

    subPackages = "cmd/pulumi-gen-kubernetes";
  };

  pulumi-resource-kubernetes = buildGoModule rec {
    inherit version vendorHash;

    pname = "pulumi-resource-kubernetes";
    src = pulumi-kubernetes-src;

    CGO_ENABLED = 0;

    subPackages = "cmd/pulumi-resource-kubernetes";

    ldflags = [
      "-w" # skip debug info
      "-X github.com/pulumi/pulumi-kubernetes/provider/v4/pkg/version.Version=v${version}"
    ];

    nativeBuildInputs = [
      pulumi-gen-kubernetes
    ];

    dontStrip = true;

    preBuild = ''
      SCHEMA_FILE=cmd/pulumi-resource-kubernetes/schema.json
      ln -s . provider
      pulumi-gen-kubernetes kinds $SCHEMA_FILE $(pwd)
      VERSION=${version} go generate cmd/pulumi-resource-kubernetes/main.go
    '';
  };
in
pulumi-resource-kubernetes
