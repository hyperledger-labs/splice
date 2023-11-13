{ }: {
# the command plugin is still in preview and not part of the default nix distribution
  packages = {
    x86_64-linux = [
      {
        url =
          "https://api.pulumi.com/releases/plugins/pulumi-resource-command-v0.9.2-linux-amd64.tar.gz";
        sha256 =
          "1fb8955dabbe81a767791e9769a32bd895dfee68d2bcc8ffd3dbd560cd5bcf0d";
      }
      {
        url =
          "https://api.pulumi.com/releases/plugins/pulumi-resource-kubernetes-cert-manager-v0.0.5-linux-amd64.tar.gz";
        sha256 =
          "449d19d54d606cf723bed76f24c821e2c963a04297e6d12f35b91576254aed2f";
      }
      {
        url =
          "https://api.pulumi.com/releases/plugins/pulumi-resource-gcp-v6.50.0-linux-amd64.tar.gz";
        sha256 =
          "bb37f86f9c7633a2b5a84cf6ea48042c604e22657d2414436ae4bf96660214b7";
      }
    ];

    x86_64-darwin = [
      {
        url =
          "https://api.pulumi.com/releases/plugins/pulumi-resource-command-v0.9.2-darwin-amd64.tar.gz";
        sha256 =
          "0b33689487f8297642336bab2e7fe6c83813d2f694a21ba397e851a5a3215696";
      }
      {
        url =
          "https://api.pulumi.com/releases/plugins/pulumi-resource-kubernetes-cert-manager-v0.0.5-darwin-amd64.tar.gz";
        sha256 =
          "0cdd4ae517668d364a2c7beed21d838fd25921e4b615fe2e2cbbc628c5308a83";
      }
      {
        url =
          "https://api.pulumi.com/releases/plugins/pulumi-resource-gcp-v6.50.0-darwin-amd64.tar.gz";
        sha256 =
          "b192f04122341df3a7f444a1609c23075fdc4213242d25de5b726ae99c6caa9e";
      }
    ];
    aarch64-linux = [
      {
        url =
          "https://api.pulumi.com/releases/plugins/pulumi-resource-command-v0.9.2-linux-arm64.tar.gz";
        sha256 =
          "0ef3b81713728d17c9d55e894c66d2c51658ce3bbe682559d1517fdd49ba126e";
      }
      {
        url =
          "https://api.pulumi.com/releases/plugins/pulumi-resource-kubernetes-cert-manager-v0.0.5-linux-arm64.tar.gz";
        sha256 =
          "33b645af0f32e36a7dc1468a65a4b55c01ccd0c0dc85506e2ff42f8fd69d0082";
      }
      {
        url =
          "https://api.pulumi.com/releases/plugins/pulumi-resource-gcp-v6.50.0-linux-arm64.tar.gz";
        sha256 =
          "c8fc27ebd418fad92cda49754b00f18228917b044ba5a5dddda825c2653e38ae";
      }
    ];
    aarch64-darwin = [
      {
        url =
          "https://api.pulumi.com/releases/plugins/pulumi-resource-command-v0.9.2-darwin-arm64.tar.gz";
        sha256 =
          "15b1f11b1d7329733337605f78eaf1612e2c3bf8bb4afbb43146f418dd6f4526";
      }
      {
        url =
          "https://api.pulumi.com/releases/plugins/pulumi-resource-kubernetes-cert-manager-v0.0.5-darwin-arm64.tar.gz";
        sha256 =
          "8d393dc2d633ed5ad7e291a70d513a340505a3dcb5684b5000c883fb4137dca3";
      }
      {
        url =
          "https://api.pulumi.com/releases/plugins/pulumi-resource-gcp-v6.50.0-darwin-arm64.tar.gz";
        sha256 =
          "8e26dbd4025968bd100c6912359b4b2c69367a125f2ab3c6ccf4295011e1de94";
      }
    ];
  };
}
