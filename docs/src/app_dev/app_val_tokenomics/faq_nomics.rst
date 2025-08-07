..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _faq_nomics:

FAQ
===

How can I find activity records that have not been used in a minting
operation and are still available for minting?

You can get this info by querying the Ledger API on the Validator node
you control.

You'll want to retrieve the active contracts, and filter for the
weighted activity reward coupons, more precisely the
`TransferInput <https://docs.dev.sync.global/app_dev/api/splice-amulet/Splice-AmuletRules.html#type-splice-amuletrules-transferinput-61796>`__
contracts

The `gRPC endpoint is
here <https://docs.digitalasset.com/build/3.3/reference/lapi-proto-docs#getactivecontracts-method-version-com-daml-ledger-api-v2>`__.

The JSON endpoint is /v2/state/active-contracts (see `OpenAPI
specification <https://docs.digitalasset.com/build/3.3/reference/json-api/openapi>`__).
