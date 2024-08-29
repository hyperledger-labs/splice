{ pkgs ? import <nixpkgs> {} }:

pkgs.stdenv.mkDerivation {
  pname = "sphinx-lint";
  version = "latest";

  src = builtins.fetchurl {
    url = "https://github.com/sphinx-contrib/sphinx-lint/archive/refs/tags/v0.9.1.tar.gz";
    sha256 = "017zbkarc9gic5h3zsgsxrbc5axxf2iq41rnm4baayckpzca699l"; # Replace with actual sha256
  };


  buildInputs = [
    pkgs.python3
    pkgs.python3Packages.pip
  ];

  buildPhase = ''
    pip install --prefix=$out sphinx-lint
  '';

  meta = {
    description = "A linter for Sphinx documentation";
    homepage = "https://github.com/sphinx-doc/sphinx";
    license = pkgs.lib.licenses.mit;
    maintainers = with pkgs.lib.maintainers; [ ];
  };
}
