..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

The mock configuration below for GCP KMS is included in ``splice-node/examples/sv-helm/kms-participant-gcp-values.yaml``:

.. literalinclude:: ../../../apps/app/src/pack/examples/sv-helm/kms-participant-gcp-values.yaml
    :language: yaml

Please refer to the `Canton documentation <https://docs.daml.com/canton/usermanual/kms/kms_gcp_setup.html>`_
for a list of supported configuration options and their meaning,
as well as for instructions on configuring authentication to the KMS.
Note again that Splice participants support the External Key Storage mode of KMS usage,
so that (per the `relevant Canton docs <https://docs.daml.com/canton/usermanual/kms/external_key_storage/external_key_storage_gcp.html>`_)
the authentication credentials you supply must correspond to a GCP service account with the following IAM permissions:

* `cloudkms.cryptoKeyVersions.create`
* `cloudkms.cryptoKeyVersions.useToDecrypt`
* `cloudkms.cryptoKeyVersions.useToSign`
* `cloudkms.cryptoKeyVersions.get`
* `cloudkms.cryptoKeyVersions.viewPublicKey`

For example, you can grant the `Cloud KMS Admin` and `Cloud KMS Crypto Operator` roles to the validator KMS service account.
