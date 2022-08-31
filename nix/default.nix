let
  spec = builtins.fromJSON (builtins.readFile ./src.json);
  src = builtins.fetchTarball {
    url = "https://github.com/${spec.owner}/${spec.repo}/archive/${spec.rev}.tar.gz";
    sha256 = spec.sha256;
  };
in (import src) {
  overlays = [(self: super: {
    sbt = super.sbt.override { jre = super.openjdk11; };
    lnav = super.callPackage ./lnav.nix {};
    haskellPackages = super.haskellPackages.override {
      overrides = hsSelf: hsSuper: {
        data-diverse = super.haskell.lib.unmarkBroken (super.haskell.lib.dontCheck hsSuper.data-diverse);
        daml2ts = hsSuper.callPackage ./daml2ts.nix {};
        proto3-wire = super.haskell.lib.dontCheck hsSuper.proto3-wire;
        proto3-suite = super.haskell.lib.dontCheck (super.haskell.lib.disableCabalFlag hsSuper.proto3-suite "swagger");
      };
    };
  })];
}
