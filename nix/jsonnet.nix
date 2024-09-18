{ stdenv, lib, fetchFromGitHub }:

stdenv.mkDerivation rec {
  pname = "jsonnet";
  version = "0.20.0";
  outputs = ["out" "doc"];

  src = fetchFromGitHub {
    rev = "v${version}";
    owner = "google";
    repo = "jsonnet";
    sha256 = "sha256-FtVJE9alEl56Uik+nCpJMV5DMVVmRCnE1xMAiWdK39Y=";
  };

  nativeBuildInputs = [ ];

  enableParallelBuilding = true;

  makeFlags = [
    "jsonnet"
    "jsonnetfmt"
    "libjsonnet.so"
  ];

  installPhase = ''
    mkdir -p $out/bin $out/lib $out/include $out/share/doc/jsonnet
    cp jsonnet $out/bin/
    cp jsonnetfmt $out/bin/
    cp libjsonnet*.so $out/lib/
    cp -a include/*.h $out/include/
  '';

  meta = {
    description = "Purely-functional configuration language that helps you define JSON data";
    maintainers = with lib.maintainers; [ benley copumpkin ];
    license = lib.licenses.asl20;
    homepage = "https://github.com/google/jsonnet";
    platforms = lib.platforms.unix;
  };
}
