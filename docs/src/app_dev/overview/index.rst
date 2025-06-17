..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _app_dev_overview:

Overview
========

Canton Network applications are used to operate business processes spanning
multiple organizations or business entities.
Use the following documentation to build Canton Network applications and get them featured on the Canton Network:

* Learn to build Canton Network applications from the tutorials, how-tos, explanations, and reference documentation maintained by Digital Asset: https://docs.digitalasset.com/build/3.3/
* Browse the currently featured apps: https://sync.global/featured-apps/
* Request an app to be featured: https://sync.global/featured-app-request/
* Learn about ongoing CIPs to feature apps from the CIP mailing list (and consider joining the list): https://lists.sync.global/g/cip-discuss/topics
* Learn about the rationale for featuring an app from the CIP repository: https://github.com/global-synchronizer-foundation/cips

.. TODO::
    call out app-dev channel and the lists for CIPs


API Overview
------------

When building an application for the Canton Network,
you will typically integrate with some of the APIs provided by the Global Synchronizer and Validator Nodes.

* Use the :ref:`ledger-api` to access the view of the ledger as seen by the parties hosted on a Validator Node and submit transaction to the ledger.
* Use the Scan APIs to access the view of the ledger as seen by all SV Nodes.
  Note that this view does not includes any of the data that is private to the parties hosted on Validator Nodes.
  Use the Ledger API to access that data.
* Use the Validator APIs to access higher-level functionality provided by the
  Splice Validator App running alongside the Canton Participant node in a
  Validator Node.

See the :ref:`validator-network-diagram` for an overview of which components in SV and Validator Nodes are
serving these APIs.

o
the components of a Validator Node, the APIs they expose, and
how connectivity to the Global Synchronizer and the Canton Network.

See their respective documentation for more details on how to use these APIs and the functionality they provide.
o
oo

https://docs.dev.sync.global/validator_operator/validator_helm.html#validator-network-diagram



Additionally, you may want to build on the APIs that are provided by the Global Synchronizer and Validator Nodes.
Use the list below to learn about these APIs and their purpose, so that you can choose the right ones to use for your application.

* :ref:`app_dev_scan_api`: these APIs provide access to the view of the ledger as seen by all SV nodes.


   #. They remove the information advantage of SVs, as they provide a read-only view of the ledger that is not available to validator nodes.
   #. They provide information


* :ref:


..
    See

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
