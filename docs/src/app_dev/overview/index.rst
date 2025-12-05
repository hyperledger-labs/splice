..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _app_dev_overview:

Overview
========

If you are a new Canton Network developer, please check out the `TL;DR for new Canton Network developers <https://docs.digitalasset.com/build/3.4/overview/tldr.html>`__.

Canton Network Applications
---------------------------

`Canton Network applications <https://docs.digitalasset.com/build/3.3/overview/introduction>`__
are used to operate business processes spanning
multiple organizations or business entities.
Use the following documentation to build Canton Network applications and get them featured on the Canton Network:

.. TODO(#1156): link to https://docs.digitalasset.com/build/current/ instead of 3.4 when it is available

* Review the summary of the Canton Network tokenomics at :ref:`app_tokenomics`.
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
  Note that this view is the one visible to the DSO party and does not include any of the data that is private to the parties hosted on Validator Nodes.
  Use the :ref:`app_dev_ledger_api` to access the data of parties hosted on the Validator Node.
* Use the :ref:`app_dev_validator_api` to access higher-level functionality provided by the
  Splice Validator App running alongside the Canton Participant node in a
  Validator Node.

See the diagram below to learn about which components serve which APIs and how they are connected to each other.

..
   _LucidChart link: https://lucid.app/lucidchart/cc18d86e-95aa-4a20-9677-160599132a3e/edit?viewport_loc=-2531%2C-3450%2C4045%2C2266%2C0_0&invitationId=inv_f7bcd7ba-780d-4887-8c24-973cc757b06e


.. image:: ../overview/images/app-connectivity-diagram.png
  :width: 800
  :alt: App Network Diagram


Splice Daml APIs Overview
-------------------------

Splice implements several decentralized applications whose on-ledger state and workflows are implemented in Daml.  An `Interface <https://docs.digitalasset.com/build/3.3/reference/daml/interfaces.html#reference-interfaces>`__
defined in Daml code is a public API that you should develop against because they are stable.
These public APIs are used in interoperability standards,
like the :ref:`Canton Network Token Standard <app_dev_token_standard_overview>`.
Use these Daml APIs to minimize the coupling between your application's Daml code and the Daml code of your dependencies.
For example, the ``Interfaces`` in the Splice implementaiton loosely
couple an application with the implementation so that a Splice upgrade avoids forcing a corresponding upgrade of an application's Daml code.

See the :ref:`app_dev_daml_api` for an overview of the Daml APIs defined in Splice and their purpose.


Splice Daml Models Overview
---------------------------

A Daml model's `Templates <https://docs.digitalasset.com/build/3.3/reference/daml/templates.html>`__ and
`Choices <https://docs.digitalasset.com/build/3.3/reference/daml/choices.html>`__ are considered internal implementation details.  For example,
the :ref:`Canton Network Token Standard <app_dev_token_standard_overview>` is the public API for working with tokens, including Canton Coin.
The :ref:`Canton Network Token Standard <app_dev_token_standard_overview>` implementation
operates on top of the :ref:`AmuletRules_Transfer <type-splice-amuletrules-amuletrulestransfer-23235>` choice (this provides backwards compatibility).
It is worthwhile and recommended to study these implementation details because you can learn a lot by examination.

Use the following resources to learn how to interact with the Daml models state and workflows.

* Learn how to read and write Daml code from:
  https://docs.digitalasset.com/build/3.3/
* Learn about the Daml packages that are part of Splice and their data models and workflows from
  :ref:`app_dev_daml_models`.
