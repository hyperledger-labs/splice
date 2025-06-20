..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _faq:

FAQ
===

Contributing
++++++++++++++++

.. glossary::

  How can I contribute to the project?

    The project welcomes contributions! Please see the
    `contribution guidelines <https://github.com/hyperledger-labs/splice/blob/main/CONTRIBUTING.md>`_
    for instructions on how to get started.


Super Validators
++++++++++++++++

.. glossary::

  Where do I find all whitelisted node operators?

    All whitelisted SVs are listed in the `public global synchronizer configurations repository <https://github.com/global-synchronizer-foundation/configs>`_.

  Where do I generate a Validator Onboarding Token?

    SVs :ref:`generate a validator onboarding token <generate_onboarding_secret>` in the SV UI.

Validators
++++++++++

.. glossary::

  How long is my Validator Onboarding Token valid?

    They can be used for 48h for secrets created by SVs and 1h for secrets created through the DevNet self-onboarding endpoint. They are one-time use only.

  How do I check my validator liveness rewards?

    You can check your validator's liveness rewards by checking the transaction history and look for transaction that mints validator rewards.

    .. image:: images/transaction_history.png
        :width: 700
        :alt: Transaction history showing a validator reward transaction.
