{
  buildGoModule,
  fetchFromGitHub,
  lib,
  yq-go
}:

buildGoModule rec {
  pname = "helm-unittest";
  version = "115964f0ab7cee6b2f1c7e7c62d2b48ecaab3e8b";

  src = fetchFromGitHub {
    owner = pname;
    repo = pname;
    rev = version;
    hash = "sha256-rsGpHk1etDTaZ5f7T4hRtbAut1HjN48JexB2xy8kB3I=";
  };

  vendorHash = "sha256-j0s0hPCGx5zn5s7dO3bSxFsAXkGxIFPfqUizxF3nkR4=";

  nativeBuildInputs = [yq-go];

  # NOTE: Remove the install and upgrade hooks.
  postPatch = ''
    sed -i '/^hooks:/,+2 d' plugin.yaml
  '';

  postInstall = ''
    install -dm755 $out/${pname}
    mv $out/bin/helm-unittest $out/${pname}/untt
    rmdir $out/bin
    install -m644 -Dt $out/${pname} plugin.yaml
  '';
}
