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

#. Select the most recent stable release with the same major and minor version as above.
#. Install that release using

   .. code-block:: bash

      curl -sSL https://get.digitalasset.com/ | sh

For more information about installing the Daml SDK, see the
`DPM installation guide <https://docs.digitalasset.com/build/3.4/dpm/dpm.html?>`__.


