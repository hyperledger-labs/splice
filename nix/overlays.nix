{ use_enterprise }:
[(self: super: {
  openapi-generator-cli = (super.openapi-generator-cli.override { jre_headless = super.openjdk21; }).overrideAttrs(oldAttrs: rec {
    # 7.0.1 causes some issues in the generated package.json which break module resolution.
    version = "6.6.0";
    jarfilename = "${oldAttrs.pname}-${version}.jar";
    src = super.fetchurl {
      url = "mirror://maven/org/openapitools/${oldAttrs.pname}/${version}/${jarfilename}";
      sha256 = "sha256-lxj/eETolGLHXc2bIKNRNvbbJXv+G4dNseMALpneRgk=";
    };
  });
  jre = super.openjdk21;
  lnav = super.callPackage ./lnav.nix {};
  canton = super.callPackage ./canton.nix {inherit use_enterprise;};
  cometbft_driver = super.callPackage ./cometbft-driver.nix {};
  daml2js = super.callPackage ./daml2js.nix {inherit use_enterprise;};
  python3 = super.python3.override {
    packageOverrides = pySelf : pySuper : rec {
      sphinx-reredirects = pySelf.callPackage ./sphinx-reredirects.nix { };
      # gsutil requires an older version of pyopenssl dependency
      pyopenssl = pySuper.pyopenssl.overridePythonAttrs (old: rec {
        version = "24.2.1";
        src = super.fetchFromGitHub {
          owner = "pyca";
          repo = "pyopenssl";
          tag = version;
          hash = "sha256-/TQnDWdycN4hQ7ZGvBhMJEZVafmL+0wy9eJ8hC6rfio=";
        };
        # we remove the docs output because it fails to build and we don't need it
        outputs = [
          "out"
          "dev"
        ];
        # tweaked to remove the sphinx hook to build docs
        nativeBuildInputs = [
          super.openssl
        ];
      });
      # downgraded to work with pyopenssl
      cryptography = (pySuper.cryptography.override {}).overridePythonAttrs (old: rec {
        pname = "cryptography";
        version = "43.0.1";
        src = pySuper.fetchPypi {
          inherit pname version;
          hash = "sha256-ID6Sp1cW2M+0kdxHx54X0NkgfM/8vLNfWY++RjrjRE0=";
        };

        cargoRoot = "src/rust";

        cargoDeps = super.rustPlatform.fetchCargoTarball {
          inherit src;
          sourceRoot = "${pname}-${version}/${cargoRoot}";
          name = "${pname}-${version}";
          hash = "sha256-wiAHM0ucR1X7GunZX8V0Jk2Hsi+dVdGgDKqcYjSdD7Q=";
        };
      });
      # downgraded together with cryptography. We can't just override it
      # as it's not exposed in pythonpackages so we need to copy vectors.nix
      cryptography-vectors = super.callPackage ./vectors.nix { buildPythonPackage = pySuper.buildPythonPackage; cryptography = pySelf.cryptography; flit-core = pySuper.flit-core; };
    };
  };
  pre-commit = super.pre-commit.overrideAttrs (old: {
    doCheck = false;
  });
  geckodriver = (
    super.callPackage ./geckodriver.nix { inherit (super.darwin.apple_sdk.frameworks) Security; }
  ).overrideAttrs (oldAttrs: {
    cargoDeps = oldAttrs.cargoDeps.overrideAttrs (oldAttrs: {
      buildPhase = builtins.replaceStrings
        [ "/etc/ssl/certs/ca-bundle.crt\n" ]
        [ "/etc/ssl/certs/ca-bundle.crt\nunset CARGO_HTTP_CAINFO NIX_SSL_CERT_FILE SSL_CERT_FILE\n" ]  # This is a workaround for Cargo to work behind MITM proxy, see https://github.com/NixOS/nixpkgs/issues/304483
        (oldAttrs.buildPhase or "");
    });
  });
  git-search-replace = super.callPackage ./git-search-replace.nix {};
  gh = super.gh.overrideAttrs (old: rec {
    version = "2.82.0";
    src = super.fetchFromGitHub {
      owner = "cli";
      repo = "cli";
      tag = "v${version}";
      hash = "sha256-0PheldNAlexi/tXHhhrPLd3YBGmcM1G+guicI2z9RYU=";
    };
  });
  sphinx-lint = super.callPackage ./sphinx-lint.nix {};
  jsonnet = super.callPackage ./jsonnet.nix {};
  pulumi-bin = super.pulumi-bin.overrideAttrs (_: previousAttrs:
    let
      inherit (super.lib.strings) hasPrefix;
      extraPackages = import ./extra-pulumi-packages.nix {};

      neededResourcePlugins = builtins.map (p: "pulumi-resource-" + p) [
        "auth0" "gcp" "google-native" "postgresql" "random" "tls" "vault" "command"
      ];

      isResourcePlugin = d: hasPrefix "pulumi-resource-" d.name;

      isNeededResourcePlugin = d: builtins.any (p: hasPrefix p d.name) neededResourcePlugins;

      keepSrc = d: isNeededResourcePlugin d || ! isResourcePlugin d;
    in {
      srcs = (builtins.filter keepSrc previousAttrs.srcs) ++ (map (x: super.fetchurl x) extraPackages.packages.${super.stdenv.hostPlatform.system});

      unpackPhase = ''
        runHook preunpack
        for src in $srcs; do
          # remove file type, the nix prefix path, the platform suffix
          stripped_name=$(basename $src .tar.gz | cut -d'-' -f2- | sed 's/\(.*\)-.*-.*$/\1/')
          echo "Unpacking $src to $stripped_name"
          mkdir -p $stripped_name
          tar xzf $src -C $stripped_name
        done
      '';

      installPhase = ''
        export PULUMI_HOME=$out
        mkdir -p $out/plugins
        mkdir -p $out/bin

        pulumi_components=$(find . -mindepth 1 -maxdepth 1 -type d ! -name "pulumi-resource*")
        for component in $pulumi_components
        do
            mv "$component"/* .
            rm -r $component
        done
        mv pulumi pulumi2
        mv pulumi2/* .
        rm -r pulumi2

        # install plugins
        plugins=$(find . -type d -name "pulumi-resource*")
        for plugin in $plugins
        do
            type=$(basename "$plugin" | sed 's/pulumi-resource-\(.*\)-v.*$/\1/')
            version=$(basename "$plugin" | sed 's/pulumi-resource-.*-v\(.*\)$/\1/')
            if [ "$type" = "gcp" ]; then
                version="v$version"
            fi
            echo "Installing $plugin with type $type and version $version"
            ./pulumi plugin install resource "$type" "$version" -f "$plugin/pulumi-resource-$type"
            rm -r $plugin
        done

        # remove unneeded language plugins
        rm -v pulumi-language-{dotnet,go,java,python,python-exec}

        ${previousAttrs.installPhase}
      '';

      dontPatchELF = true;
    });
})]
