{
  description = "splice nix setup for development";

  inputs = {
    nixpkgs.url = "nixpkgs/nixpkgs-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem
      (system:
        let enterprise_pkgs = import nixpkgs { inherit system; overlays = import ./overlays.nix { use_enterprise = true; }; };
            oss_pkgs = import nixpkgs { inherit system; overlays = import ./overlays.nix { use_enterprise = false; }; };
            enterprise_x86Pkgs =
              if system == "aarch64-darwin"
              then import nixpkgs { system = "x86_64-darwin"; overlays = import ./overlays.nix { use_enterprise = true; }; }
              else enterprise_pkgs;
            oss_x86Pkgs =
              if system == "aarch64-darwin"
              then import nixpkgs { system = "x86_64-darwin"; overlays = import ./overlays.nix { use_enterprise = false; }; }
              else oss_pkgs;
            npmPkgs = oss_pkgs.callPackage ./npmpkgs/default.nix { inherit system; };

        in
        {
          packages = {
            # Forwarded so we can get the path from sbt.
            reredirects = oss_pkgs.python3.pkgs.sphinx-reredirects;
          };
          # For now, the default is enterprise. Use `nix develop path:nix#oss` to use the OSS version.
          devShells.default = import ./shell.nix {
            inherit npmPkgs;
            x86Pkgs = enterprise_x86Pkgs;
            pkgs = enterprise_pkgs;
            use_enterprise = true;
          };
          devShells.oss = import ./shell.nix {
            inherit npmPkgs;
            x86Pkgs = oss_x86Pkgs;
            pkgs = oss_pkgs;
            use_enterprise = false;
          };
        }
      );
}
