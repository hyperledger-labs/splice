..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _token_standard:

Token Standard APIs
###################

API References
==============

Refer to `CIP-0056 <https://github.com/global-synchronizer-foundation/cips/blob/main/cip-0056/cip-0056.md#details>`_ for more context on the APIs.

.. note::

  TODO(#4582): adjust the text here and below to reflect that there is a V2 version of the token standard APIs.


Token Metadata
--------------

  .. toctree::
    :maxdepth: 1

    Daml reference for V1<../api/splice-api-token-metadata-v1/index>
    OpenAPI reference for V1 <./openapi/token_metadata>

Note that there is no V2 of the Token Metadata API, as the V1 API is reused across both V1 and V2 of the token standard.

Holding
-------

This allows implementation of a `Portfolio View <https://github.com/global-synchronizer-foundation/cips/blob/main/cip-0056/cip-0056.md#wallet-client--portfolio-view>`_.

  .. toctree::
    :maxdepth: 1

    Daml reference for V1 <../api/splice-api-token-holding-v1/index>
    Daml reference for V2 <../api/splice-api-token-holding-v2/index>

Transfer Events
---------------

These are used by wallets to parse transfers from the Ledger API's transaction history and display them in the wallet's transaction history UI.
They were newly added as part of the V2 token standard.

  .. toctree::
    :maxdepth: 1

    Daml reference for V2 <../api/splice-api-token-transfer-events-v2/index>

.. TODO(#4581): add open api docs for V2

Transfer Instruction
--------------------

This allows implementation of `Direct Peer-to-Peer / Free of Payment (FOP) Transfers <https://github.com/global-synchronizer-foundation/cips/blob/main/cip-0056/cip-0056.md#direct-peer-to-peer--free-of-payment-fop-transfer-workflow>`_.

  .. toctree::
    :maxdepth: 1

    Daml reference for V1 <../api/splice-api-token-transfer-instruction-v1/index>
    Daml reference for V2 <../api/splice-api-token-transfer-instruction-v2/index>
    OpenAPI reference for V1 <./openapi/transfer_instruction>

.. TODO(#4581): add open api docs for V2

Allocation
----------

This allows implementation of `Delivery versus Payment (DVP) Transfer Workflows <https://github.com/global-synchronizer-foundation/cips/blob/main/cip-0056/cip-0056.md#delivery-versus-payment-dvp-transfer-workflows>`_,
jointly with the Allocation Instruction and Allocation Request APIs below.

  .. toctree::
    :maxdepth: 1

    Daml reference for V1 <../api/splice-api-token-allocation-v1/index>
    Daml reference for V2 <../api/splice-api-token-allocation-v2/index>
    OpenAPI reference for V1 <./openapi/allocation>

.. TODO(#4581): add open api docs for V2

Allocation Instruction
----------------------

  .. toctree::
    :maxdepth: 1

    Daml reference for V1 <../api/splice-api-token-allocation-instruction-v1/index>
    Daml reference for V2 <../api/splice-api-token-allocation-instruction-v2/index>
    OpenAPI reference for V1 <./openapi/allocation_instruction>

.. TODO(#4581): add open api docs for V2

Allocation Request
------------------

  .. toctree::
    :maxdepth: 1

    Daml reference for V1 <../api/splice-api-token-allocation-request-v1/index>
    Daml reference for V2 <../api/splice-api-token-allocation-request-v2/index>


Implementation Utilities
------------------------

See the following package for converting between V1 and V2 API versions, and for
default implementations of token standard choices.

  .. toctree::
    :maxdepth: 1

    Daml reference for both V1 and V2 <../api/splice-token-standard-utils/index>

Using token standard choices from custom Daml code
---------------------------------------------------

.. toctree::
  :maxdepth: 1

  ../api/splice-util-token-standard-wallet/index
