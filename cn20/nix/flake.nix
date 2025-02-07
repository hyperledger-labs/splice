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
          devShells.default = import ./shell.nix { inherit pkgs x86Pkgs npmPkgs; };
        }
      );
}
