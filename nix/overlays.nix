{ use_enterprise }:
[(self: super: {
  # We need the old version as our code is not compatible with the new one.
  # Just overwriting the version does not work as they changed the build code to be
  # incompatible with the new one.
  openapi-generator-cli = super.callPackage ./openapi-generator-cli.nix {};
  jre = super.openjdk21;
  lnav = super.callPackage ./lnav.nix {};
  canton = super.callPackage ./canton.nix {inherit use_enterprise;};
  cometbft_driver = super.callPackage ./cometbft-driver.nix {};
  daml2js = super.callPackage ./daml2js.nix {inherit use_enterprise;};
  python3 = super.python3.override {
    packageOverrides = pySelf : pySuper : rec {
      sphinx-reredirects = pySelf.callPackage ./sphinx-reredirects.nix { };
    };
  };
  pre-commit = super.pre-commit.overrideAttrs (old: {
    doCheck = false;
  });
  geckodriver = (
    super.callPackage ./geckodriver.nix {}
  ).overrideAttrs (oldAttrs: {
    cargoDeps = oldAttrs.cargoDeps.overrideAttrs (oldAttrs: {
      buildPhase = builtins.replaceStrings
        [ "/etc/ssl/certs/ca-bundle.crt\n" ]
        [ "/etc/ssl/certs/ca-bundle.crt\nunset CARGO_HTTP_CAINFO NIX_SSL_CERT_FILE SSL_CERT_FILE\n" ]  # This is a workaround for Cargo to work behind MITM proxy, see https://github.com/NixOS/nixpkgs/issues/304483
        (oldAttrs.buildPhase or "");
    });
  });
  git-search-replace = super.callPackage ./git-search-replace.nix {};
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
