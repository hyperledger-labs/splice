..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

Scalability
~~~~~~~~~~~

.. _party_scaling:

Number of Parties per Validator
-------------------------------

While the underlying Canton participant supports up to 1 million
parties per node, limitations in the validator app currently mean that
only up to 200 parties are supported when the validator app is
involved. The limit of 200 parties is expected to be lifted in the future which
should make those workarounds no longer required.


Concretely, the limit of 200 parties applies to:

1. Parties not using external signing that are onboarded through the validator
   APIs or any other party for which a ``WalletAppInstall`` contract has been created separately.
2. Parties using external signing for which the onboarding contracts
   have been set up through ``/v0/admin/external-party/setup-proposal``
   or where a ``ValidatorRight`` contract with the ``validator`` party set to the validator operator party has been created manually.

It does not apply to:

1. Parties not using external signing that have been created through
   the Ledger API or Canton admin API and have not been onboarded
   through the validator APIs or have associated ``WalletAppInstall``
   contracts.
2. Parties using external signing for which no corresponding
   ``ValidatorRight`` exists with the ``validator`` party set to the
   validator operator party. Note that
   ``/v0/admin/external-party/setup-proposal`` does set up such a
   ``ValidatorRight`` contract so this API must be avoided. Using the
   ``ExternalPartySetupProposal`` contract directly for setting
   preapprovals is possible but the ``validator`` party must be set to
   a different party than the validator operator party. This also
   implies that you need to write your own automation to renew the
   preapproval when it expires. Refer to :ref:`docs
   <preapproval_renewal>` for more details.


Bypassing the Limit
^^^^^^^^^^^^^^^^^^^

The preferred option of bypassing the limit is to set up an external
party either directly through the `Canton APIs for external signing
<https://docs.digitalasset.com/build/3.3/explanations/external-signing/external_signing_overview.html>`_
or ``/v0/admin/external-party/topology/{generate,submit}`` on the
validator API or but *not* use the endpoints under
``/v0/admin/external-party/setup-proposal``.

If you do not need preapprovals, this is sufficient.

If you do need to create preapprovals, you must ensure that you do not
create ``ValidatorRight`` contract with the ``validator`` party set to
the validator operator. The best option for this is to build your own
replacement Daml workflow for ``ExternalPartySetupProposal`` to gather
authorization from both the external party and the validator operator
which then exercises ``AmuletRules_CreateTransferPreapproval`` but
does not create a ``ValidatorRight`` contract. As long as the
``provider`` on the resulting ``TransferPreapproval`` is the validator
operator party, the renewal automation for transfer preapprovals in
the validator will still continue functioning. If you set it to a
different party, you need to build your own renewal automation.

Implications of bypassing the Limit
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

Note that bypassing the validator limit does mean that the validator
app does not process any contracts for that party. Most notably, this
means that there is no reward minting automation running for that
party including the fact that ``ValidatorRewardCoupon`` activity
records generated for that party cannot be minted by the validator
operator as this relies on the ``ValidatorRight`` contract. If this is
required, you must build your own minting automation.

You also cannot use any of the validator endpoints under
``/v0/admin/external-party/`` for this party, e.g., to initiate a
transfer. Instead, interact with the external party through the :ref:`token standard <token_standard>` over the ledger API.
