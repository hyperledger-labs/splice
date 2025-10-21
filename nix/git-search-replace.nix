{ lib, python3Packages }:

python3Packages.buildPythonPackage rec {
  pname = "git-search-replace";
  version = "1.0.3";

  pyproject = true;
  build-system = [ "setuptools" ];

  src = python3Packages.fetchPypi {
    inherit pname version;
    sha256 = "sha256-5L/ygt8FvArw+CdndkuvZAR9QPji7zFVfmogsqZkvBw=";
  };

  propagatedBuildInputs = with python3Packages; [
    plumbum
  ];

  postInstall = ''
    ln -s $out/bin/git-search-replace.py $out/bin/gsr
  '';

  meta = with lib; {
    description = "A utility on top of git for project-wide search-and-replace that includes filenames too";
    homepage = "https://github.com/da-x/git-search-replace";
    license = licenses.bsd3;
  };
}
