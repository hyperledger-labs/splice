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
