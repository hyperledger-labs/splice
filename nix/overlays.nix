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
})]
