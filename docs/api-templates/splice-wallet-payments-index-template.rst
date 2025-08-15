..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _reference_docs_splice_wallet_payments:

splice-wallet-payments docs
===========================

.. note::
   **Deprecated** (since splice-0.4.10):
   the modules below and their workflows are deprecated and should not be used in new applications.

   To implement a Canton Coin payment, use the :ref:`Token Standard <app_dev_token_standard_overview>` allocation workflow instead.
   Note that this immediately makes your application compatible with all Token Standard assets,
   and allows all of them to be used as payment methods in your application, if desired.

   If you need to implement a subscription-like payment, then we recommend doing so via regular renewals
   on top of token standard allocations in your application. See the ``License_Renew`` choice and its
   `tests <https://github.com/digital-asset/cn-quickstart/blob/8b15f38d157d1f88e8da4345f477fffc0cfc4b8f/quickstart/daml/licensing-tests/daml/Licensing/Scripts/TestLicense.daml#L207-L221>`_
   in the Canton Network QuickStart repo for an
   `example of how to do this <https://github.com/digital-asset/cn-quickstart/blob/8b15f38d157d1f88e8da4345f477fffc0cfc4b8f/quickstart/daml/licensing/daml/Licensing/License.daml#L44>`_.

   The workflows in the modules below are not compatible with the token standard,
   and will not be supported in the future.

.. toctree::
   :maxdepth: 3
   :titlesonly:

{{{body}}}
