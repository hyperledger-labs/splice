..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _app_dev_overview:

Overview
========

Canton Network Applications
---------------------------

`Canton Network applications <https://docs.digitalasset.com/build/3.3/overview/introduction>`__
are used to operate business processes spanning
multiple organizations or business entities.
Use the following documentation to build Canton Network applications and get them featured on the Canton Network:

.. TODO(#1156): link to https://docs.digitalasset.com/build/current/ instead of 3.4 when it is available

* Learn to build Canton Network applications from the tutorials, how-tos, explanations, and reference documentation at
  https://docs.digitalasset.com/build/3.3/
* Browse the currently featured apps: https://sync.global/featured-apps/
* Request your app to be featured: https://sync.global/featured-app-request/

  * These requests are published on the `Tokenomics mailing list <https://lists.sync.global/g/tokenomics/topics>`__.
    Browse that list for inspiration and to see what other apps are being built.

* Join the app development discussion in the `#gsf-global-synchronizer-appdev <https://app.slack.com/client/T03T53E10/C08FQRCRFUN>`__
  Slack channel by sending a request to operations@sync.global for your Slack organization to be added to the channel.


RPC APIs Overview
-----------------

When building an application for the Canton Network,
you will typically integrate with some of the HTTP or gRPC APIs provided by the Global Synchronizer and Validator Nodes.
Use the guidance below to learn which ones to use and how to use them.

* Use the :ref:`app_dev_ledger_api` to access the view of the ledger as seen by the parties hosted on a Validator Node and submit transaction to the ledger.
* Use the :ref:`app_dev_scan_api` to access the view of the ledger and its infrastructure as seen by all SV Nodes.
  Note that this view is the one visible to the DSO party and does not includes any of the data that is private to the parties hosted on Validator Nodes.
  Use the :ref:`app_dev_ledger_api` to access the data of parties hosted on the Validator Node.
* Use the :ref:`app_dev_validator_api` to access higher-level functionality provided by the
  Splice Validator App running alongside the Canton Participant node in a
  Validator Node.

See the :ref:`validator-network-diagram` for details on the components running as part of a Validator Node's and the APIs they provide.


Splice Daml APIs Overview
-------------------------

Splice defines Daml APIs that decouple different applications on the Canton Network.
For example, the Allocation API from the Canton Network Token Standard decouples the
registry apps that manage who owns what token from apps that manage the settlement of
trades.

See the :ref:`app_dev_daml_api` for an overview of the Daml APIs defined in Splice and their purpose.


Splice Daml Models Overview
---------------------------

Splice implements several decentralized applications whose on-ledger state and workflows are implemented in Daml.
Use the following resources to learn how to interact with this state and workflows.

* Learn how to read and write Daml code from:
  https://docs.digitalasset.com/build/3.3/
* Learn about the Daml packages that are part of Splice and their data models and workflows from
  :ref:`app_dev_daml_models`.

