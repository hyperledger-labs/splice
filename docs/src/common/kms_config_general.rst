..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

Please refer to the `Canton documentation on configuring KMS support <https://docs.daml.com/canton/usermanual/kms/kms_setup.html>`_ for determining the right configuration options to match your desired KMS provider and setup.
We provide minimal Helm configuration examples for Google Cloud (GCP) KMS and Amazon Web Services (AWS) KMS below.

.. warning::

   The GCP and AWS KMS drivers are available only for licensed users of Canton Enterprise.

Whatever KMS provider you choose, please note:

* Values in the ``kms`` section of the participant Helm chart are implicitly mapped to the Canton participant ``crypto.kms`` config.
  This implies that all configuration keys supported by Canton are supported, not only the ones shown in the examples above.
  Key names in camelCase are automatically converted to kebab-case.
* For setting extra environment variables and mounting files to configure authentication to the KMS,
  you can use the ``.additionalEnvVars``, ``.extraVolumeMounts``, and ``.extraVolumes`` fields of the Splice participant Helm chart
  (see the examples).
* Make sure that your KMS configuration is always included in the values files you pass to ``helm install participant ...`` or ``helm upgrade participant ...``.
