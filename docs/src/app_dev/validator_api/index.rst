..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _app_dev_validator_api:

Validator API
=============

The Validator App is part of a :term:`CN Validator` or :term:`CN Supervalidator` node.
It connects to a Canton participant and provides the following functionality:

  * It manages the Canton participant.
    Examples are automatically setting up the participant's connection to :term:`Canton Network` synchronizer,
    uploading DAR files, or managing daml parties.
  * It automates core :term:`Canton Network` daml workflows (except those related to supervalidator operations).
    Examples are minting validator rewards or executing recurring :term:`Canton Coin` payments.
  * It exposes a REST API for interacting with core :term:`Canton Network` daml workflows (except those related to supervalidator operations).
    Examples are users programmatically managing their :term:`CN Wallet`.

The REST API consists of a large number of endpoints with different purposes,
which are listed below grouped as follows:

.. list-table::
   :widths: 10 30
   :header-rows: 1

   * - API
     - Purpose
   * - :ref:`validator-api-user-wallet`
     - Users interacting with their wallets
   * - :ref:`validator-api-user-wallet-internal`
     - Internal components interacting with user wallets
   * - :ref:`validator-api-external-signing`
     - External signing for :term:`Canton Coin`
   * - :ref:`validator-api-user-management`
     - Managing users hosted by the (super)validator node
   * - :ref:`validator-api-internal`
     - Operators managing the (super)validator node
   * - :ref:`validator-api-ans`
     - Used for the Amulet Name Service
   * - :ref:`validator-api-scan-proxy`
     - BFT proxy to the public scan API

.. _validator-api-user-wallet:

User wallet API
---------------

These endpoints are intended for users to programmatically interact with their wallets.

**Authorization:** Authentication with a JWT token as described in :ref:`app-auth`,
where the subject claim of the token is the user whose wallet the endpoint operates on.

**Backwards compatibility:** External API with backwards compatibility guarantees.

**Reference:** For details, see the `wallet-external.yaml <https://raw.githubusercontent.com/hyperledger-labs/splice/refs/heads/main/apps/wallet/src/main/openapi/wallet-external.yaml>`__ OpenAPI spec.

.. _validator-api-user-wallet-transfer-offers:

Transfer Offers
~~~~~~~~~~~~~~~

Transfer offers are used to in a two-step workflow to transfer Canton Coin between users:

  * The sender creates a ``Splice.Wallet.TransferOffer`` daml contract.
  * The receiver accepts the offer, which immediately transfers the agreed coin.

.. list-table::
   :widths: 10 39
   :header-rows: 1

   * - Endpoint
     - Description
   * - **POST** /v0/wallet/transfer-offers
     - Create a transfer offer
   * - **POST** /v0/wallet/transfer-offers/{tracking_id}/status
     - Check the status of a transfer offer
   * - **GET** /v0/wallet/transfer-offers
     - List transfer offers

.. _validator-api-user-wallet-buying-traffic:

Buying Traffic
~~~~~~~~~~~~~~

Traffic on the :term:`CN Global Synchronizer` is limited.
Every validator has a budget of traffic that they can use,
and daml transactions submitted to the synchronizer consume this traffic.
A certain amount of traffic is free, additional traffic has to be bought with Canton Coin.

Any user can buy traffic for any validator.
Buying traffic is a multi-step process:

  * The user creates a ``Splice.Wallet.BuyTrafficRequest`` daml contract.
  * The users wallet automation picks up the request, burns the required coin from the users wallet,
    and increases the traffic budget of the target validator.

.. list-table::
   :widths: 10 30
   :header-rows: 1

   * - Endpoint
     - Description
   * - **POST** /v0/wallet/buy-traffic-requests
     - Create a request to buy traffic
   * - **POST** /v0/wallet/buy-traffic-requests/{tracking_id}/status
     - Check the status of a buy traffic request

.. _validator-api-user-wallet-internal:

Internal user wallet API
------------------------

These endpoints are used internally to interact with a users wallet,
for example by the web frontend of the user wallet.

.. todo:: Some of these endpoints should be public and documented.

**Authorization:** Authentication with a JWT token as described in :ref:`app-auth`,
where the subject claim of the token is the user whose wallet the endpoint operates on.

**Backwards compatibility:** Internal API with no guarantees.

**Reference:** For details, see the `wallet-internal.yaml <https://raw.githubusercontent.com/hyperledger-labs/splice/refs/heads/main/apps/wallet/src/main/openapi/wallet-internal.yaml>`__ OpenAPI spec.

.. list-table::
   :widths: 10
   :header-rows: 1

   * - Endpoint
   * - **POST** /v0/wallet/transfer-offers/{contract_id}/accept
   * - **POST** /v0/wallet/transfer-offers/{contract_id}/reject
   * - **POST** /v0/wallet/transfer-offers/{contract_id}/withdraw
   * - **GET** /v0/wallet/app-payment-requests
   * - **POST** /v0/wallet/app-payment-requests/{contract_id}/reject
   * - **POST** /v0/wallet/app-payment-requests/{contract_id}/accept
   * - **GET** /v0/wallet/app-payment-requests/{contract_id}
   * - **GET** /v0/wallet/subscription-requests
   * - **POST** /v0/wallet/subscription-requests/{contract_id}/reject
   * - **POST** /v0/wallet/subscription-requests/{contract_id}/accept
   * - **DELETE** /v0/wallet/subscription-requests/{contract_id}
   * - **GET** /v0/wallet/subscription-requests/{contract_id}
   * - **DELETE** /v0/wallet/cancel-featured-app-rights
   * - **POST** /v0/wallet/transfer-preapproval
   * - **POST** /v0/wallet/transfer-preapproval/send
   * - **GET** /v0/wallet/balance
   * - **GET** /v0/wallet/amulets
   * - **GET** /v0/wallet/accepted-app-payments
   * - **GET** /v0/wallet/accepted-transfer-offers
   * - **GET** /v0/wallet/app-reward-coupons
   * - **GET** /v0/wallet/subscription-initial-payments
   * - **GET** /v0/wallet/subscriptions
   * - **GET** /v0/wallet/sv-reward-coupons
   * - **POST** /v0/wallet/transactions
   * - **GET** /v0/wallet/validator-faucet-coupons
   * - **GET** /v0/wallet/validator-liveness-activity-records
   * - **GET** /v0/wallet/validator-reward-coupons
   * - **POST** /v0/wallet/self-grant-feature-app-right
   * - **POST** /v0/wallet/tap
   * - **GET** /v0/wallet/user-status

.. _validator-api-external-signing:

External Signing API
--------------------

These endpoints are used to implement external signing of :term:`Canton Coin` transactions.

External signing is a Canton feature allows setting up a party such that transaction submissions must be signed by keys held outside of the participant.
For more information on external signing in general, see the
`example <https://github.com/digital-asset/canton/tree/release-line-3.2/community/app/src/pack/examples/08-interactive-submission/v1>`__,
`service protobuf definition <https://github.com/digital-asset/canton/blob/release-line-3.2/community/ledger-api/src/main/protobuf/com/daml/ledger/api/v2/interactive/interactive_submission_service.proto>`__,
and `readme <https://github.com/digital-asset/canton/blob/release-line-3.2/community/ledger-api/src/main/protobuf/com/daml/ledger/api/v2/interactive/README.md>`__
in Canton.

For the common case of wanting to set up an external party in a topology where the executing, preparing and confirming participant
are the same node and that party should hold and transfer Canton Coin, the validator provides high-level APIs.

  #. Use ``/v0/admin/external-party/topology/*`` to set up an external party
  #. Use ``/v0/admin/external-party/setup-proposal`` to start setting up a ``Splice.Wallet.TransferPreapproval`` daml contract for the external party,
     which allows the party to send and receive Canton Coin without having to approve individual :ref:`transfer offers <validator-api-user-wallet-transfer-offers>`.
  #. Use ``/v0/admin/external-party/setup-proposal/*`` to finish setting up the transfer preapproval.
  #. Use ``/v0/admin/external-party/transfer-preapproval/*`` to send Canton Coin to other parties.
  #. Use ``/v0/admin/external-party/balance`` to check the balance of the external party.

**Authorization:** Authentication with any valid JWT token as described in :ref:`app-auth`.

**Backwards compatibility:** Internal API with no guarantees.

**Reference:** For details, see the `validator-internal.yaml <https://raw.githubusercontent.com/hyperledger-labs/splice/refs/heads/main/apps/validator/src/main/openapi/validator-internal.yaml>`__ OpenAPI spec.

.. list-table::
   :widths: 10
   :header-rows: 1

   * - Endpoint
   * - **POST** /v0/admin/external-party/topology/generate
   * - **POST** /v0/admin/external-party/topology/submit
   * - **GET** /v0/admin/external-party/setup-proposal
   * - **POST** /v0/admin/external-party/setup-proposal
   * - **POST** /v0/admin/external-party/setup-proposal/prepare-accept
   * - **POST** /v0/admin/external-party/setup-proposal/submit-accept
   * - **GET** /v0/admin/transfer-preapprovals
   * - **GET** /v0/admin/transfer-preapprovals/by-party/{receiver-party}
   * - **DELETE** /v0/admin/transfer-preapprovals/by-party/{receiver-party}
   * - **POST** /v0/admin/external-party/transfer-preapproval/prepare-send
   * - **POST** /v0/admin/external-party/transfer-preapproval/submit-send
   * - **GET** /v0/admin/external-party/balance

.. _validator-api-user-management:

User management API
-------------------

These endpoints are used to manage users hosted on the validator node.

Users can either onboard themselves (``/v0/register``),
or an admin may onboard arbitrary users (``/v0/admin/users``).

**Authorization:** Authentication with a JWT token as described in :ref:`app-auth`,
where the subject claim of the token is the validator operator user (for ``/v0/admin/users``),
or the user onboarding itself (for ``/v0/register``).

**Backwards compatibility:** Internal API with no guarantees.

**Reference:** For details, see the `validator-internal.yaml <https://raw.githubusercontent.com/hyperledger-labs/splice/refs/heads/main/apps/validator/src/main/openapi/validator-internal.yaml>`__ OpenAPI spec.

.. list-table::
   :widths: 10
   :header-rows: 1

   * - Endpoint
   * - **GET** /v0/admin/users
   * - **POST** /v0/admin/users/offboard
   * - **POST** /v0/admin/users
   * - **POST** /v0/register

.. _validator-api-internal:

Validator management API
------------------------

These endpoints are used by validator and supervalidator operators to manage their node.
There is no need to call these endpoints unless instructed so by an operational manual,
such as :ref:`validator_operator` or :ref:`sv_operator`.


**Authorization:** Authentication with a JWT token as described in :ref:`app-auth`,
where the subject claim of the token is the validator operator user.

**Backwards compatibility:** Internal API with no guarantees.

**Reference:** For details, see the `validator-internal.yaml <https://raw.githubusercontent.com/hyperledger-labs/splice/refs/heads/main/apps/validator/src/main/openapi/validator-internal.yaml>`__ OpenAPI spec.

.. list-table::
   :widths: 10
   :header-rows: 1

   * - Endpoint
   * - **GET** /v0/admin/participant/identities
   * - **GET** /v0/admin/participant/global-domain-connection-config
   * - **GET** /v0/admin/domain/data-snapshot

.. _validator-api-ans:

ANS API
-------

These endpoints are used to interact with the :term:`Amulet Name Service` (ANS).
The (ANS) is a service that allows parties to buy a globally unique, human readable name for a time period mapped to their party.
Users can request the creation of new ANS entries, upon which a subscription payment request is created.
Once the payment is accepted in the wallet UI, the entry is created and the user can use it to refer to their party.

**Authorization:** Authentication with a JWT token as described in :ref:`app-auth`,
where the subject claim of the token is the user who is requesting the new ANS entry.

**Backwards compatibility:** External API with backwards compatibility guarantees.

**Reference:** For details, see the `ans-external.yaml <https://raw.githubusercontent.com/hyperledger-labs/splice/refs/heads/main/apps/validator/src/main/openapi/ans-external.yaml>`__ OpenAPI spec.

.. list-table::
   :widths: 10 30
   :header-rows: 1

   * - Endpoint
     - Description
   * - **POST** /v0/entry/create
     - Requests the creation of a new entry
   * - **GET** /v0/entry/all
     - Lists all entries

.. _validator-api-scan-proxy:

Scan Proxy API
--------------

These endpoints implement a BFT proxy to the public scan API.
They have the same interfaces as the equally named endpoints in the public :ref:`app_dev_scan_api`.

If the validator app is part of a :term:`CN Validator` node,
then each call to one of these endpoints is broadcast to the scan services of multiple supervalidator nodes,
and the consensus result is returned to the caller.
Use these endpoints instead of calling a scan service directly to avoid the need to trust a single supervalidator node.

If the validator app is part of a :term:`CN Supervalidator` node,
then each call to one of these endpoints is simply forwarded to the scan service of the same node.

**Authorization:** Authentication with any valid JWT token as described in :ref:`app-auth`.

**Backwards compatibility:** See the corresponding endpoint in the :ref:`app_dev_scan_api`.

**Reference:** For details, see the `scan-proxy.yaml <https://raw.githubusercontent.com/hyperledger-labs/splice/refs/heads/main/apps/validator/src/main/openapi/scan-proxy.yaml>`__ OpenAPI spec.

.. list-table::
   :widths: 10
   :header-rows: 1

   * - Endpoint
   * - **GET** /v0/scan-proxy/amulet-rules
   * - **POST** /v0/scan-proxy/ans-rules
   * - **GET** /v0/scan-proxy/dso-party-id
   * - **GET** /v0/scan-proxy/open-and-issuing-mining-rounds
   * - **GET** /v0/scan-proxy/ans-entries
   * - **GET** /v0/scan-proxy/ans-entries/by-name/{name}
   * - **GET** /v0/scan-proxy/ans-entries/by-party/{party}
   * - **GET** /v0/scan-proxy/featured-apps/{provider_party_id}
   * - **GET** /v0/scan-proxy/transfer-command-counter/{party}
   * - **GET** /v0/scan-proxy/transfer-command/status
   * - **GET** /v0/scan-proxy/transfer-preapprovals/by-party/{party
