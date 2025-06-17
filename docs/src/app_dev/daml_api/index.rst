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



Canton Network Token Standard APIs (CIP-0056)
---------------------------------------------

.. TODO(#651): inline and adapt the text from the CIP-0056.md file here, so that it is visible in the docs

* See the `text of the CIP-0056 <https://github.com/global-synchronizer-foundation/cips/blob/main/cip-0056/cip-0056.md>`__
  for an overview of the APIs that are part of the Canton Network Token Standard.
* See the `README in its source-code <https://github.com/hyperledger-labs/splice/tree/main/token-standard#readme>`__ for background on how to use the APIs.
* See the reference docs below for the Daml interfaces that are part of the Canton Network Token Standard;
  or `read the source code <https://github.com/hyperledger-labs/splice/tree/main/token-standard>`__.

   .. toctree::
      :maxdepth: 1

      ../api/splice-api-token-metadata-v1/index
      ../api/splice-api-token-holding-v1/index
      ../api/splice-api-token-transfer-instruction-v1/index
      ../api/splice-api-token-allocation-request-v1/index
      ../api/splice-api-token-allocation-instruction-v1/index
      ../api/splice-api-token-allocation-v1/index
      ../api/splice-api-token-burn-mint-v1/index

.. TODO(#1074): also add links to OpenAPI docs for the REST API parts of these APIs

Featured App Activity Markers API (CIP-0047)
--------------------------------------------

* See the `text of the CIP-0047 <https://github.com/global-synchronizer-foundation/cips/blob/main/cip-0047/cip-0047.md>`__
  for its background on its design and its specification.
* See the reference docs below for the Daml interfaces that are part of the Featured App Activity Markers API;
  or `read the source code <https://github.com/hyperledger-labs/splice/blob/main/daml/splice-api-featured-app-v1/daml/Splice/Api/FeaturedAppRightV1.daml>`__.

   .. toctree::
      :maxdepth: 1

      ../api/splice-api-featured-app-v1/index
