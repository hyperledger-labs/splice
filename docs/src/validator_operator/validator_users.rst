..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _validator-users:

Users, Parties and Wallets in the Splice Wallet
===============================================

Canton distinguishes between parties and users, as documented in detail in the
`Canton docs <https://docs.daml.com/app-dev/parties-users.html>`_. In essence,
a party is an identity on-ledger, while a user represents an off-ledger entity
that can be associated with one or more parties.

By default, when a user logs in for the first time in the wallet, and presses the "Onboard yourself" button,
the Validator allocates a fresh party, with a fresh Party ID, and associates that user
with the newly allocated party. As part of validator initialization, a party is automatically created for the
Validator Operator. The user provided during installation as the `validatorWalletUser` will be
associated with this party as its primary party.

More users can be configured such that their primary party is that of the validator operator.
In effect, when such users login to the wallet UI, they will be accessing the wallet of the validator
operator. Note that this will be the same wallet accessed by different users, with currently
no support for finer grained permissions per user.

In order to associate a user with the party of the validator operator, the following steps are required:

1. Do not onboard the user through the UI, to avoid the validator allocating a new party for the user.
2. Create a new user in your OIDC provider, and obtain its user ID, e.g. `auth0|123456789`.
   Save it in a USER environment variable: ``export USER=auth0|123456789``.
3. Obtain validator operator credentials for interacting with the validator's API.
   One way of achieving this is by inspecting the network activity logs of the wallet when logged
   in as the operator, and copying from there the Bearer token used for API requests.
   Save it in a TOKEN environment variable: ``export TOKEN=<content of the token you obtained>``
4. Obtain the PartyID of the validator operator. You can find that in the wallet UI when
   logged in as the operator, in the top right corner, next to the logout button.
   Save it in a PARTY_ID environment variable: ``export PARTY_ID=<PartyID of the operator>``
5. Run the following command to associate the user with the party of the validator operator:

.. code-block:: bash

    curl -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
    --data-raw "{\"party_id\":\"$PARTY_ID\",\"name\":\"$USER\"}" \
    https://<URL of your wallet>/api/validator/v0/admin/users

6. The user can now login to the wallet UI, and will be accessing the wallet of the validator operator.
   Note that the user should not be greeted with the "Onboard yourself" button, as the user is already
   onboarded through the API call above. If you do see the button, it means that something has gone wrong
   in the process above (do not click the button!).


Disable wallet and wallet automation
-----------------------------------------------

To disable the wallet HTTP server and the wallet automation, update the ``validator-values.yaml`` file with ``enableWallet: false``

.. literalinclude:: ../../../apps/app/src/pack/examples/sv-helm/validator-values.yaml
    :language: yaml
    :start-after: ENABLEWALLET_START
    :end-before: ENABLEWALLET_END
