{ stdenv, lib, python3Packages }:

python3Packages.buildPythonPackage rec {
  pname = "sphinx-lint";
  version = "0.9.1";
  format = "pyproject";

  # for some reason fetchPypi does not work (404), so we use fetchurl
  src = builtins.fetchurl {
    url = "https://github.com/sphinx-contrib/sphinx-lint/archive/refs/tags/v0.9.1.tar.gz";
    sha256 = "017zbkarc9gic5h3zsgsxrbc5axxf2iq41rnm4baayckpzca699l";
  };

  nativeBuildInputs = [
    python3Packages.hatchling
    python3Packages.hatch-vcs
  ];

  buildInputs = [
    python3Packages.polib
    python3Packages.regex
  ];

  meta = {
    description = "A Sphinx extension to lint your documentation";
    homepage = "https://github.com/sphinx-contrib/sphinx-lint";
  };
}
