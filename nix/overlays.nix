[(self: super: {
  jre = super.openjdk11;
  openapi-generator-cli = super.openapi-generator-cli.override { jre = super.openjdk11; };
  lnav = super.callPackage ./lnav.nix {};
  canton = super.callPackage ./canton.nix {};
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
  pulumi-bin = super.pulumi-bin.overrideAttrs (_: previousAttrs:
    let
      inherit (super.lib.strings) hasPrefix;

      neededResourcePlugins = [
        "auth0" "gcp" "google-native" "kubernetes" "postgresql" "random" "tls" "vault"
      ];

      isResourcePlugin = d: hasPrefix "pulumi-resource-" d.name;

      isRequired = d: builtins.any (p: hasPrefix ("pulumi-resource-" + p) d.name) neededResourcePlugins;

      keepSrc = d: isRequired d || ! isResourcePlugin d;
    in {
      srcs = builtins.filter keepSrc previousAttrs.srcs;

      installPhase = ''
        # remove unneeded language plugins
        rm -v pulumi-language-{dotnet,go,java,python,python-exec}

        ${previousAttrs.installPhase}
      '';

      dontPatchELF = true;
    });
})]
