..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _app_dev_overview:

Overview
========

An application on the Canton Network is defined by the kind of APIs it integrates with.
We explain all the APIs available from the Global Synchronizer and a validator node :ref:`here <splice_app_apis>`.

At a high-level, we can distinguish between the following two kinds of apps:

* Read-Only Apps: these apps build functionality by using read-only APIs, and thus cannot mutate the ledger.
* Daml Apps: these apps integrate directly or indirectly with the Ledger API
  to create new contracts or exercise choices on the ledger to drive on-ledger workflows.

You can find more information in the following sub-sections:

.. toctree::

   read-only-apps
   daml-apps


.. todo::

   - add overview over types of APIs and which apps need what
   - for Daml apps

      - explain app provider and app user nodes
      - refer to the TSA training for the in-depth explanation of building Daml apps


.. todo:: split into validator/wallet api, scan api, daml, ledger API
.. todo:: add section on testing including spinning up localnet
.. todo:: add section on deployment for app devs, e.g., DAR uploads
.. todo::

    Add overview of how to integrate with CC at the Daml level

    - use the token standard
    - mention the `AppPaymentRequestFlow` as deprecated
    - clearly mark splice subscription API as deprecated

    Where possible refer to splice Daml code as the primary source; consider adding Daml docs where they are missing for this to work.


.. toctree::

  version_information
  splice_app_apis
