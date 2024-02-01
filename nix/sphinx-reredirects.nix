{ lib
, buildPythonPackage
, fetchPypi
, sphinx
, setuptools
}:

buildPythonPackage rec {
  pname = "sphinx-reredirects";
  version = "0.1.3";
  format = "pyproject";

  src = fetchPypi {
    pname = "sphinx_reredirects";
    inherit version;
    hash = "sha256-VuIi0oX3bJRP03DzatOhpmEDqItVLpfT0kpiK7lGXeg=";
  };

  propagatedBuildInputs = [
    sphinx
    setuptools
  ];

  pythonImportsCheck = [
    "sphinx_reredirects"
  ];

}
