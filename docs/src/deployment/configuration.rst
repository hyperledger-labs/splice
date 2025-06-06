..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _configuration:

Configuring deployed apps
=========================

All the apps have an extended set of configuration options which might need tuning based on different scenarios.
These configurations are accepted in the `HOCON <https://github.com/lightbend/config/blob/main/HOCON.md>`__ format.

Adding ad-hoc configuration
+++++++++++++++++++++++++++

Every app accepts extra configuration through environment variables.
All the environment variables passed to the apps, that start with `ADDITIONAL_CONFIG` will be processed and the configuration will be applied when the app starts.

.. note::
    Example env: ADDITIONAL_CONFIG_EXAMPLE="canton.example.key=value"


The full configuration for each app can be observed in the scala code,
with the configuration key being kebab case compared to the camel case in the scala code:

-  `ValidatorAppConfig.scala <https://github.com/hyperledger-labs/splice/blob/main/apps/validator/src/main/scala/org/lfdecentralizedtrust/splice/validator/config/ValidatorAppConfig.scala#L141>`__
-  `SvAppConfig.scala <https://github.com/hyperledger-labs/splice/blob/main/apps/sv/src/main/scala/org/lfdecentralizedtrust/splice/sv/config/SvAppConfig.scala#L199>`__
-  `ScanAppConfig.scala <https://github.com/hyperledger-labs/splice/blob/main/apps/scan/src/main/scala/org/lfdecentralizedtrust/splice/scan/config/ScanAppConfig.scala#L28>`__

Furthermore, the participant and other synchronizer components can be configured independently as well. Further info on such configurations can be found in the `daml docs <https://docs.daml.com/canton/usermanual/static_conf.html>`__.

.. todo:: point to the release that these docs are built from; or inline the source code or Scaladoc to avoid confusion


Helm charts support
^^^^^^^^^^^^^^^^^^^

The helm charts can be configured through the value `additionalEnvVars`, which passes the values as environment variables to the apps.

.. code-block:: yaml

    additionalEnvVars:
        - name: ADDITIONAL_CONFIG_EXAMPLE
          value: canton.example.key=value
