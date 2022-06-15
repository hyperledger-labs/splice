let
  pkgs = import (import ./nix/default.nix) {};
in pkgs.mkShell {
  buildInputs = with pkgs; [
    nodejs
    sbt
    sphinx
    sphinx-autobuild
    python3Packages.sphinx_rtd_theme
  ];
}
