let
  pkgs = import ./nix/default.nix {};
  # sphinx-autobuild is currently broken on M1 due to
  # https://github.com/NixOS/nixpkgs/issues/174457#issuecomment-1137385758
  x86Pkgs = if builtins.currentSystem == "aarch64-darwin" then import ./nix/default.nix { system = "x86_64-darwin"; } else pkgs;
in pkgs.mkShell {
  buildInputs = with pkgs; [
    nodejs
    sbt
    sphinx
    x86Pkgs.sphinx-autobuild
    python3Packages.sphinx_rtd_theme
  ];
}
