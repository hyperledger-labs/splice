..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _app_dev_overview:

Overview
========

`Canton Network applications <https://docs.digitalasset.com/build/3.3/overview/introduction>`__
are used to operate business processes spanning
multiple organizations or business entities.
Use the following documentation to build Canton Network applications and get them featured on the Canton Network:

.. TODO(#1156): link to https://docs.digitalasset.com/build/current/ instead of 3.4 when it is available

* Learn to build Canton Network applications from the tutorials, how-tos, explanations, and reference documentation at
  https://docs.digitalasset.com/build/3.3/
* Browse the currently featured apps: https://sync.global/featured-apps/
* Request an app to be featured: https://sync.global/featured-app-request/
* Learn about ongoing CIPs to feature apps from the CIP mailing list (and consider joining the list): https://lists.sync.global/g/cip-discuss/topics
* Learn about the rationale for featuring an app from the CIP repository: https://github.com/global-synchronizer-foundation/cips
* Join the app development discussion in the `#gsf-global-synchronizer-appdev <https://app.slack.com/client/T03T53E10/C08FQRCRFUN>`__
  Slack channel by sending a request to operations@sync.global for your Slack organization to be added to the channel.


.. rubric:: REST/gRPC APIs

When building an application for the Canton Network,
you will typically integrate with some of the APIs provided by the Global Synchronizer and Validator Nodes.

* Use the :ref:`app_dev_ledger_api` to access the view of the ledger as seen by the parties hosted on a Validator Node and submit transaction to the ledger.
* Use the :ref:`app_dev_scan_api` to access the view of the ledger and its infrastructure as seen by all SV Nodes.
  Note that this view is the one visible to the DSO party and does not includes any of the data that is private to the parties hosted on Validator Nodes.
  Use the :ref:`app_dev_ledger_api` to access that data.
* Use the :ref:`app_dev_validator_api` to access higher-level functionality provided by the
  Splice Validator App running alongside the Canton Participant node in a
  Validator Node.

See the :ref:`validator-network-diagram` for details on the components running as part of a Validator Node's and the APIs they provide.


.. rubric:: Splice Daml APIs

Splice defines Daml APIs that decouple different applications on the Canton Network.




.. rubric:: Splice Daml Models

Splice implements a number of decentralized applications whose on-ledger state and workflows are implemented in Daml.
Use the following resources to learn how to interact with this state and workflows.

* Learn how to read and write Daml code using the documentation at:
  https://docs.digitalasset.com/build/3.3/
* Learn about the Daml packages that are part of Splice and their data models and workflows from
  :ref:`app_dev_daml_api`.

.. todo:: split into data schemas and interface packages


    .. todo::

        Add overview of how to integrate with CC at the Daml level

        - use the token standard
        - mention the `AppPaymentRequestFlow` as deprecated
        - clearly mark splice subscription API as deprecated


.. todo:: fix the toc-tree below to not nest below

.. toctree::

  version_information
  splice_app_apis
