..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _validator-security:

==================
Security Hardening
==================

.. _validator-kms:

Using an external KMS for managing participant keys
+++++++++++++++++++++++++++++++++++++++++++++++++++

.. include:: ../common/kms_participants_context.rst

In the following, we describe how to configure a validator so that its participant keys are managed by an external KMS.
This guide assumes that you are using the :ref:`Helm-based deployment <k8s_validator>` of the validator.
KMS usage is not currently supported for :ref:`Docker Compose-based deployments <compose_validator>`.

.. _validator-kms-migrating:

Migrating an existing validator to use an external KMS
------------------------------------------------------

.. include:: ../common/kms_migration_context.rst

Our recommended approach for switching to use KMS is to:

1. Set up a fresh validator from scratch with the desired KMS configuration. (Rest of this guide.)
2. Transfer all relevant assets from the existing non-KMS validator to the new KMS-enabled validator.
3. Retire the non-KMS validator.

Configuring a fresh validator to use an external KMS
----------------------------------------------------

Only configuration changes to the ``splice-participant`` Helm chart are required to deploy a KMS-enabled validator.

.. include:: ../common/kms_config_general.rst

Also recall that you need to deploy a **fresh** participant in order for KMS to be used correctly,
which implies that you will need to setup the remaining validator components afresh as well (see :ref:`above <validator-kms-migrating>`).

Google Cloud KMS
^^^^^^^^^^^^^^^^

.. include:: ../common/kms_config_gcp.rst

Amazon Web Services KMS
^^^^^^^^^^^^^^^^^^^^^^^

.. include:: ../common/kms_config_aws.rst

.. TODO(DACH-NY/canton-network-internal#479): Add a section about offline root namespace keys
