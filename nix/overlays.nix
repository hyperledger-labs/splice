[(self: super: {
  jre = super.openjdk11;
  openapi-generator-cli = super.openapi-generator-cli.override { jre = super.openjdk11; };
  lnav = super.callPackage ./lnav.nix {};
  canton = super.callPackage ./canton.nix {};
  cometbft_driver = super.callPackage ./cometbft-driver.nix {};
  jsonapi = super.callPackage ./jsonapi.nix {};
  daml_pbs = super.callPackage ./daml_pbs.nix {};
  haskellPackages = super.haskellPackages.override {
    overrides = hsSelf: hsSuper: {
      data-diverse = super.haskell.lib.unmarkBroken (super.haskell.lib.dontCheck hsSuper.data-diverse);
      daml2ts = super.haskell.lib.justStaticExecutables (hsSuper.callPackage ./daml2ts.nix {});
      proto3-wire = super.haskell.lib.dontCheck hsSuper.proto3-wire;
      proto3-suite = super.haskell.lib.dontCheck (super.haskell.lib.disableCabalFlag hsSuper.proto3-suite "swagger");
    };
  };
  python3 = super.python3.override {
    packageOverrides = pySelf : pySuper : {
        sphinx-reredirects = pySelf.callPackage ./sphinx-reredirects.nix { };
    };
  };
  jsonnet = super.callPackage ./jsonnet.nix {};
  pulumi-bin = super.pulumi-bin.overrideAttrs (_: previousAttrs:
    let
      inherit (super.lib.strings) hasPrefix;

      # Note: remove once https://github.com/pulumi/pulumi-kubernetes/issues/2481 is resolved
      #       and available in a release
      pulumi-resource-kubernetes = super.callPackage ./pulumi-kubernetes { };

      neededResourcePlugins = builtins.map (p: "pulumi-resource-" + p) [
        "auth0" "gcp" "google-native" "postgresql" "random" "tls" "vault"
      ];

      isResourcePlugin = d: hasPrefix "pulumi-resource-" d.name;

      isNeededResourcePlugin = d: builtins.any (p: hasPrefix p d.name) neededResourcePlugins;

      keepSrc = d: isNeededResourcePlugin d || ! isResourcePlugin d;
    in {
      srcs = builtins.filter keepSrc previousAttrs.srcs;

      installPhase = ''
        # remove unneeded language plugins
        rm -v pulumi-language-{dotnet,go,java,python,python-exec}

        # copy patched pulumi kubernetes provider
        cp --reflink=auto ${pulumi-resource-kubernetes}/bin/pulumi-resource-kubernetes .

        ${previousAttrs.installPhase}
      '';

      dontPatchELF = true;
    });
})]
