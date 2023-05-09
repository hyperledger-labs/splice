{ lib
, buildPythonPackage
, fetchPypi
, sphinx
}:

buildPythonPackage rec {
  pname = "sphinx-reredirects";
  version = "0.1.1";
  format = "setuptools";

  src = fetchPypi {
    pname = "sphinx_reredirects";
    inherit version;
    hash = "sha256-RRmkXTFskhxGMnty/kEOHnoy/lFpR0EpYCCwygCPvO4=";
  };

  propagatedBuildInputs = [
    sphinx
  ];

  pythonImportsCheck = [
    "sphinx_reredirects"
  ];

}
