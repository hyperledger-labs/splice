..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

You can optionally configure the validator to automatically create transfer offers
to other parties on the network whenever the balance of certain parties that it hosts
exceeds a certain threshold.

Whenever the balance of `<senderPartyID>` exceeds `maxBalanceUSD`, the validator
will automatically create a transfer offer to `<receiverPartyId>`, for an amount that
leaves `minBalanceUSD` in the sender's wallet. Note that you will need to know the
party IDs of both the sender and receiver, which can be copied from the wallet UIs
of the respective users (in the top right corner). This therefore needs to be applied
to the Helm chart in a second step after the initial deployment, once the party IDs are known.

Whenever the validator receives a transfer offer from `<senderPartyID>` to `<receiverPartyId>`,
it will automatically accept it. Similarly to sweeps, party IDs must be known in order to
apply this configuration.
