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
involved.

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

In most cases, the preferred option of bypassing the limit is to set up
an external party directly through the `Canton APIs for external
signing
<https://docs.digitalasset.com/build/3.3/explanations/external-signing/external_signing_overview.html>`_.
Either don't create a preapproval for the party or if you would like
one ensure that you set it up with a provider party that is different
from the validator operator as described above.

Note that bypassing the validator limit does mean that the validator
app does not process any contracts for that party. Most notably, this
means that there is no reward minting automation running for that
party including the fact that ``ValidatorRewardCoupon`` activity
records generated for that party cannot be minted by the validator
operator as this relies on the ``ValidatorRight`` contract. If this is
required, you must build your own minting automation.

The limit of 200 parties is expected to be lifted in the future which
should make those workarounds no longer required.
