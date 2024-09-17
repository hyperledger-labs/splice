{
  description = "my project description";

  inputs = {
    nixpkgs.url = "nixpkgs/nixpkgs-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem
      (system:
        let pkgs = import nixpkgs { inherit system; overlays = import ./overlays.nix; };
            x86Pkgs =
              if system == "aarch64-darwin"
              then import nixpkgs { system = "x86_64-darwin"; overlays = import ./overlays.nix; }
              else pkgs;
            npmPkgs = pkgs.callPackage ./npmpkgs/default.nix { inherit system; };

        in
        {
          packages = {
            # Forwarded so we can get the path from sbt.
            reredirects = pkgs.python3.pkgs.sphinx-reredirects;
          };
          devShells.default = import ./shell.nix { inherit pkgs x86Pkgs npmPkgs; };
        }
      );
}
