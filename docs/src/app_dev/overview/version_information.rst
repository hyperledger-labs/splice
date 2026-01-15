..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

Version Information
===================

The following versions of Canton and the Daml SDK were used to build this Splice release:

.. list-table::
   :header-rows: 0

   * - Canton version used for validator and SV nodes
     - |canton_version|
   * - Daml SDK version used to compile ``.dars``
     - |daml_sdk_version|
   * - Daml SDK version used for Java and TS codegens
     - |daml_sdk_tooling_version|

.. TODO(#472): remove this note once we are using official SDK releases

.. note::
  The SDK version numbers do not match the
  `Github releases of the Daml SDK <https://github.com/digital-asset/daml/releases>`__,
  but instead refer to the artifact versions used inside a Daml SDK release.
  A potential source of confusion that we expect to resolve in the future,
  https://github.com/hyperledger-labs/splice/issues/472.



Installing a Compatible Daml SDK
--------------------------------

You are not required to install the exact same Daml SDK versions used to build this Splice release.
These versions are provided for reference only.
``.dar`` files built by older 3.x Daml SDKs are generally compatible with the Canton version used in this Splice release.

For testing the interaction of your app with a Validator Node, we do recommend to use the Canton version used in this Splice release,
which is the case if you deploy your Validator Node using the :ref:`validator deployment instructions <validator_operator>`.

To profit from the most recent features and bug fixes of the Daml SDK, we recommend to use a recent Daml SDK release
that has the *same major and minor version* as the Canton version used in this Splice release.

Follow these steps to install a recent, compatible OSS Daml SDK version:

#. Select a release with the same major and minor version as the Canton version used in this Splice release
   from the `Github releases of the Daml SDK <https://github.com/digital-asset/daml/releases>`__.
   For example, the `3.3.0-snapshot.20250603.0 <https://github.com/digital-asset/daml/releases/tag/v3.3.0-snapshot.20250603.0>`__ release.
#. Install that release using

   .. code-block:: bash

      curl -sSL https://get.daml.com/ | sh -s 3.3.0-snapshot.20250603.0

   while replacing ``3.3.0-snapshot.20250603.0`` with the version you selected in the first step.

For more information about installing the Daml SDK, see the
`Daml assistant installation guide <https://docs.digitalasset.com/build/3.4/component-howtos/smart-contracts/assistant.html#install>`__.


