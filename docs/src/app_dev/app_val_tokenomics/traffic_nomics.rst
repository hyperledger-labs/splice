..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _traffic_nomics:

Traffic Purchase
=================

Traffic credits are used for all submissions to the Global Synchronizer. Traffic costs are charged for both sending and receiving Global
Synchronizer transaction data.

A validator increases its traffic credit balance by burning CC at the
current USD to Canton Coin conversion rate and a USD/MB price. When
needed, the operator of a validator (or a third-party service provider)
burns CC in exchange for traffic credits. The CC burned creates a
`ValidatorRewardCoupon <https://docs.dev.sync.global/app_dev/api/splice-amulet/Splice-Amulet.html#type-splice-amulet-validatorrewardcoupon-76808>`__
where the user is the validator operator of the validator hosting the
purchasing party. There is no application involved so no
`AppRewardCoupon <https://docs.dev.sync.global/app_dev/api/splice-amulet/Splice-Amulet.html#type-splice-amulet-apprewardcoupon-57229>`__
is created.

Note that traffic credits are used even when a ``Transfer`` operation or
transaction does not succeed.
