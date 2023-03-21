.. _sv_bootstrap:

Bootstrapping an SV
===================

These pages give a step-by-step guide how to deploy your own supervalidator (SV) node to the Canton network.

Prerequisites
-------------

The availability of an onboarded and fully operational validator node under your control is a requirement to operating an SV node.
Make sure you have completed the steps in :ref:`self_hosted_validator`,
including the steps to setup a Canton participant node.

Like for hosting a validator, you also need to enable the GCP DA Canton DevNet VPN.
If you can view this documentation, you already enabled the VPN successfully.

Generating an SV identity
-------------------------

SV operators are identified by a human-readable name and an EC public key.
This identification is stable across deployments of the Canton network.
You are, for example, expected to reuse your SV name and public key between (test-)network resets.

Use the following shell commands to generate a keypair in the format expected by the SV node software: ::

  # Generate the keypair
  openssl ecparam -name prime256v1 -genkey -noout -out sv-keys.pem

  # Encode the keys
  public_key_base64=$(openssl ec -in sv-keys.pem -pubout -outform DER 2>/dev/null | base64 -w 0)
  private_key_base64=$(openssl pkcs8 -topk8 -nocrypt -in sv-keys.pem -outform DER 2>/dev/null | base64 -w 0)

  # Output the keys
  echo "public-key = \"$public_key_base64\""
  echo "private-key = \"$private_key_base64\""

  # Clean up
  rm sv-keys.pem

These commands should result in an output similar to ::

  public-key = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE1eb+JkH2QFRCZedO/P5cq5d2+yfdwP+jE+9w3cT6BqfHxCd/PyA0mmWMePovShmf97HlUajFuN05kZgxvjcPQw=="
  private-key = "MEECAQAwEwYHKoZIzj0CAQYIKoZIzj0DAQcEJzAlAgEBBCBsFuFa7Eumkdg4dcf/vxIXgAje2ULVz+qTKP3s/tHqKw=="

Store both keys in a safe location.

The `public-key` and your desired SV name need to be approved by a threshold of currently active SVs in order for you to be able to join the network as a SV.
For DevNet, send the `public-key` and your desired SV name to your point of contact at Digital Asset and wait for confirmation that your SV identity has been approved and configured at existing SV nodes.

Configure your SV node
----------------------

..
  TODO(#3593) Finish this

1) Configure your SV name, public key and private key.
   (Note: Additional forms of SV authentication / private key storage will be available in the future.)
2) Configure the address of a SV sponsor. (Note: For DevNet, this is always DA's own "SV1".)
3) Start the SV node software for the first time and wait for bootstrapping to complete automatically.
