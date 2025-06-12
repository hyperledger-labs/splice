..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _sv-security:

==================
Security Hardening
==================

Third-party Daml apps
+++++++++++++++++++++

.. include:: ../common/sv_extra_dars_notice.rst

.. _sv-kms:

Using an external KMS for managing participant keys
+++++++++++++++++++++++++++++++++++++++++++++++++++

.. include:: ../common/kms_participants_context.rst

In the following, we describe how to configure an SV so that its participant keys are managed by an external KMS.
This guide assumes that you are following :ref:`sv-helm` for deploying your SV.

Official support for the KMS-based operation of sequencers and mediators that are part of an SV deployment is planned for a future release.

.. _sv-kms-migrating:

Migrating an existing SV to use an external KMS for participant keys
--------------------------------------------------------------------

.. include:: ../common/kms_migration_context.rst

Our recommended approach for switching to use KMS for SV participant keys is to:

1. Coordinate with the other SV operators to offboard your current SV.
2. Set up a fresh SV from scratch with the desired KMS configuration. (Rest of this guide.)
3. Transfer all relevant assets from the existing non-KMS SV to a validator or the new KMS-enabled SV.
4. Retire the non-KMS SV.

Configuring a fresh SV to use an external KMS
---------------------------------------------

In addition to :ref:`configuration changes to the participant Helm chart <sv-kms-participant>` itself,
you will also need to
:ref:`change the way in which your CometBFT governance key is managed <sv-kms-cometbft-key>` compared to the default setup.

.. _sv-kms-cometbft-key:

External management of CometBFT governance key
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

By default, the CometBFT governance key is managed transparently by the SV app, using the participant for key generation and storage.
The specific way in which this is realized is not supported for KMS-enabled participants.
Therefore, SV operators that wish to use an external KMS for managing their participant keys must manage the CometBFT governance key
of their SV externally.

This involves the following steps:

1. Generate a new CometBFT governance key.
2. Configure your SV app to use this externally generated key.

Generating a CometBFT governance key
====================================

Use the following shell commands to generate a keypair in the expected format: ::

  # Generate the private key
  openssl genpkey -algorithm ed25519 -out cometbft-governance-keys.pem

  # Extract and encode the keys
  public_key_base64=$(openssl pkey -in cometbft-governance-keys.pem -pubout -outform DER | tail -c 32 | base64 | tr -d "\n")
  private_key_base64=$(openssl pkey -in cometbft-governance-keys.pem -outform DER | tail -c 32 | base64 | tr -d "\n")

  echo "{"
  # Output the keys
  echo "  \"publicKey\": \"$public_key_base64\","
  echo "  \"privateKey\": \"$private_key_base64\""
  echo "}"

  # Clean up
  rm cometbft-governance-keys.pem

..
  Based on `scripts/generate-cometbft-governance-keys.sh`

These commands should result in an output similar to ::

  {
    "public": "A9tWyYq/HIJ3B73ym1eIUV8yqnDBligGJUE8463CBUM=",
    "private": "FDG16PaSh9hGLu2fXzEHmTECMjSyQuZnEg+w5HKCEtg="
  }

Save this output to a file, e.g., ``cometbft-governance-keys.json``.

Configuring SV app to use the externally generated CometBFT governance key
==========================================================================

You inject the externally generated CometBFT governance key into the SV app via
storing it in a k8s secret named ``splice-app-sv-cometbft-governance-key``.

Assuming that your SV deployment resides in the ``sv`` namespace,
use the following command to create the secret from the JSON file generated above: ::

  kubectl create secret --namespace sv generic splice-app-sv-cometbft-governance-key \
    --from-literal=publicKey="$(jq -r .public cometbft-governance-keys.json)" \
    --from-literal=privateKey="$(jq -r .private cometbft-governance-keys.json)"

To instruct the SV app to use the externally managed CometBFT governance key instead of generating a fresh one itself,
set the ``cometBFT.externalGovernanceKey`` value in the ``splice-sv-node`` Helm chart to ``true``.
(You can comment out the respective line in ``splice-node/examples/sv-helm/sv-values.yaml``.)

.. _sv-kms-participant:

Configuring participant to use an external KMS
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

Beyond the :ref:`changes to the SV app deployment <sv-kms-cometbft-key>` described above,
the setup of an SV participant to use an external KMS is identical to the
:ref:`setup of a validator participant with KMS <validator-kms>`.
It involves configuration changes to the ``splice-participant`` Helm chart that depend on the KMS provider you choose.

.. include:: ../common/kms_config_general.rst

Also recall that you need to deploy a **fresh** participant in order for KMS to be used correctly,
which implies that you will need to set up the remaining SV components afresh as well (see :ref:`above <sv-kms-migrating>`).

Google Cloud KMS
================

.. include:: ../common/kms_config_gcp.rst

Amazon Web Services KMS
=======================

.. include:: ../common/kms_config_aws.rst
