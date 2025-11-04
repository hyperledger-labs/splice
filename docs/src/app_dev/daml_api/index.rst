..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _app_dev_daml_api:

Splice Daml APIs
================

The APIs below are published by Splice to aid decoupling different Canton Network applications.
Consider using them to decouple your code from the upgrading cycles of your dependencies,
when building Daml code that interacts with workflows of other apps in the Canton Network.

These APIs are not mandatory to use. Feel free to build your own Daml APIs, potentially
using the APIs below as inspiration.


.. _app_dev_token_standard_overview:

Canton Network Token Standard APIs (CIP-0056)
---------------------------------------------

Refer to the :ref:`Token Standard documentation section <token_standard>`.


.. _featured_app_activity_markers_api:

Featured App Activity Markers API (CIP-0047)
--------------------------------------------

* See the `text of the CIP-0047 <https://github.com/global-synchronizer-foundation/cips/blob/main/cip-0047/cip-0047.md>`__
  for its background on its design and its specification.

* See the reference docs below for the Daml interfaces that are part of the Featured App Activity Markers API;
  or `read the source code <https://github.com/hyperledger-labs/splice/blob/main/daml/splice-api-featured-app-v1/daml/Splice/Api/FeaturedAppRightV1.daml>`__.

   .. toctree::
      :maxdepth: 1

      ../api/splice-api-featured-app-v1/index

* The utility package below provides templates that allow to delegate the usage of featured app rights
  to other parties for the purpose of executing token standard actions.
  Use these templates to earn app rewards on token standard operations, and to potentially share some of them with your users.

   .. toctree::
      :maxdepth: 1

      ../api/splice-util-featured-app-proxies/index


.. _wallet_how_to_earn_featured_app_rewards:

How to earn featured app rewards for wallet user activity
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

Assuming you are a wallet provider that runs a validator node for your users,
then there are two options for you to earn featured app rewards for the activity of your wallet users.
They are complementary, so you probably want to implement both of them:

1. Have your wallet users request your
   :ref:`featured wallet provider party <how_to_become_a_featured_application>`
   to maintain :ref:`Canton Coin transfer preapproval <preapprovals>`
   contracts for them.
   Whenever such a contract is used to directly transfer CC to one of your wallet users,
   a featured app coupon is created for your wallet provider party.

2. Setup your wallet frontend such that the transactions submitted by your users
   create a featured app activity marker for your wallet provider party.

   For token standard interactions, we recommend to use the ``WalletUserProxy`` template
   :ref:`as explained below <how_to_use_walletuserproxy>`. It enables you to earn featured app rewards
   on sending all CN tokens (including CC) and to earn featured app rewards
   when your users accept, reject, or withdraw a CN token transfer offer.
   It further provides the option to execute
   :ref:`bulk transfers <type-splice-util-featuredapp-walletuserproxy-walletuserproxybatchtransfer-93002>`
   of CN tokens.

   If required, you can also :ref:`write custom Daml code <token_standard_usage_custom_daml_code>` that creates the featured app activity markers
   for your wallet provider party on the relevant user actions.
   Feel free to reuse parts of the ``WalletUserProxy`` implementation for that purpose.
   Make sure to publish your code using a package name under your control to avoid upgrading conflicts.

Earning featured app rewards for direct transfers of non-CC tokens to your wallet users
is currently not possible without special support from the app provider of the respective token.


.. _how_to_use_walletuserproxy:

How to use the WalletUserProxy to earn featured app rewards
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

Assuming you are a wallet provider that runs a validator node for your users,
you can use the ``WalletUserProxy``
:ref:`template <type-splice-util-featuredapp-walletuserproxy-walletuserproxy-95528>`
to get credit for the activity of your wallet users as follows.

1. Apply for a featured app right for your wallet provider party, as explained on
   :ref:`how_to_become_a_featured_application`.
2. Extract the latest version of the ``splice-util-featured-app-proxies.dar`` file
   from the release bundle (|bundle_download_link|).
3. Upload the extracted ``.dar`` file to your validator node.
4. Create one ``WalletUserProxy`` contract for your featured wallet provider party
   using the Ledger API of your validator node.
5. Adjust your wallet frontend as follows:

     1. Make the frontend fetch your wallet provider party's ``FeaturedAppRight`` contract and
        the ``WalletUserProxy`` contract.

        You can read them from the Ledger API of your validator node using any user
        that has ``ReadAs`` rights for your wallet provider party.
        We recommend to cache and serve them using your wallet backend for performance reasons.

     2. Make the frontend construct the transfer transaction by exercising the ``WalletUserProxy_TransferFactory_Transfer``
        :ref:`choice <type-splice-util-featuredapp-walletuserproxy-walletuserproxytransferfactorytransfer-32457>`
        instead of the ``TransferFactory_Transfer`` choice of the token standard API.
        Include the fetched ``FeaturedAppRight`` and the ``WalletUserProxy`` contracts as disclosed contracts
        alongside the disclosed contracts received from the registry API
        when exercising the choice.

        See this `Daml test script <https://github.com/hyperledger-labs/splice/blob/main/daml/splice-util-featured-app-proxies-test/daml/Splice/Util/FeaturedApp/IntegrationTests/TestWalletUserProxy.daml#L47>`__
        for a complete example of how to construct the choice.



Additional Splice Daml APIs
---------------------------

The app provider of an asset registry is not necessarily the same as the party controlling the minting and burning of tokens.
A typical example are tokens that are bridged from another network. The
following API targets that use-case; and thus enables to decouple the upgrade cycles of an asset registry from the ones of the bridging app.

   .. toctree::
      :maxdepth: 1

      ../api/splice-api-token-burn-mint-v1/index

The API is built in a similar style as the token standard APIs, but is not part
of the token standard. In particular, implementors of the token standard are not required to implement this API.

Nevertheless the API definition is guaranteed to be stable, and can be used by for the purpose explained above.
If there were changes to the API, then they would be published as a new version of the API using a fresh package name,
so that both the old and the new version can be used in parallel.
