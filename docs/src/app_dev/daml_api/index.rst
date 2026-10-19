..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _app_dev_daml_api:

Reward Assignment API (CIP-0104)
--------------------------------

As part of implementing `CIP-0104 <https://github.com/canton-foundation/cips/blob/main/cip-0104/cip-0104.md>`__
the following Daml API was introduced:

   .. toctree::
      :maxdepth: 1

      ../api/splice-api-reward-assignment-v1/index

The API is meant for apps whose ultimate beneficiaries of rewards are different from
the app provider party (e.g., decentralized apps).
The API allows them to assign the rewards they receive for their app to their ultimate beneficiaries.

The API is based on a Daml interface, so that apps can use it from their Daml
code without incurring a static dependency on ``splice-amulet`` and the
corresponding upgrading challenges. The reward assignment choice can also
be called directly from the Ledger API for apps that don't use custom Daml code.

Note that the coupons always specify the original app provider party for whose activity
the reward coupon was created.


Featured App Activity Markers API (CIP-0047)
--------------------------------------------

.. important::

   On networks where traffic-based app rewards as described in `CIP-0104 <https://github.com/canton-foundation/cips/blob/main/cip-0104/cip-0104.md>`__ are enabled,
   the Featured App Activity Markers API will become irrelevant.
   On such networks, the API is still supported, but no rewards can be earned using it.
   We recommend apps to stop creating featured app activity markers once CIP-0104 is enabled.

   The documentation here is provided for apps that need to integrate with app rewards prior
   to CIP-0104 being enabled to avoid unnecessary traffic costs.

* See the `text of the CIP-0047 <https://github.com/global-synchronizer-foundation/cips/blob/main/cip-0047/cip-0047.md>`__
  for its background on its design and its specification.

* See the reference docs below for the Daml interfaces that are part of the Featured App Activity Markers API;
  or `read the source code <https://github.com/canton-network/splice/blob/main/daml/splice-api-featured-app-v2/daml/Splice/Api/FeaturedAppRightV2.daml>`__.

   .. toctree::
      :maxdepth: 1

      ../api/splice-api-featured-app-v2/index

   or the old API:

   .. toctree::
      :maxdepth: 1

      ../api/splice-api-featured-app-v1/index

* The utility package below provides templates that allow to delegate the usage of featured app rights
  to other parties for the purpose of executing token standard actions.
  Use these templates to earn app rewards on token standard operations, and to potentially share some of them with your users.

   .. toctree::
      :maxdepth: 1

      ../api/splice-util-featured-app-proxies/index

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
