..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0



Overview
========

.. todo:: add section on deployment topology and hardware requirements

   - add overview over types of apps
   - add overview over types of APIs and which apps need what
   - for Daml apps

      - explain app provider and app user nodes
      - refer to the TSA training for the in-depth explanation of building Daml apps


.. todo:: split into validator/wallet api, scan api, daml, ledger API
.. todo:: add section on testing including spinning up localnet
.. todo:: add section on deployment for app devs, e.g., DAR uploads

Version Information
===================

.. list-table::
   :header-rows: 0

   * - Canton version used for validator and SV nodes
     - |canton_version|
   * - Daml SDK version used to compile ``.dars``
     - |daml_sdk_version|
   * - Daml SDK version used for Java and TS codegens
     - |daml_sdk_tooling_version|

Testing
=======

.. toctree::

   localnet


API Reference
=============

.. todo::

   add overview over each API type, e.g., overview over scan

.. todo::

    Add overview of how to integrate with CC at the Daml level

    - use the token standard
    - mention the `AppPaymentRequestFlow` as deprecated
    - clearly mark splice subscription API as deprecated

    Where possible refer to splice Daml code as the primary source; consider adding Daml docs where they are missing for this to work.


.. toctree::

   splice_app_apis/index
   daml
