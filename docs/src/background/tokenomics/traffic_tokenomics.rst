..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _traffic_tokenomics:

Traffic Purchase
=================

Traffic credits are used for all submissions to the Global Synchronizer. Traffic costs are charged for sending all data based on the size of the messages and its delivery cost. However only the sending validator node is charged. Please refer to :ref:`traffic` for more details.

A validator increases its traffic credit balance by burning CC at the
current USD-to-Canton-Coin conversion rate (a USD/MB price). When
needed, the operator of a validator (or a third-party service provider)
burns CC in exchange for traffic credits. The CC burned creates a
:ref:`ValidatorRewardCoupon <type-splice-amulet-validatorrewardcoupon-76808>`
with the amount of CC burnt and where the ``user`` is the validator operator of the validator hosting the
purchasing party. There is no application involved so no
:ref:`AppRewardCoupon <type-splice-amulet-apprewardcoupon-57229>`
is created.

To avoid running out of traffic credits, the ability to perform automatic traffic purchases is provided and recommended.   Please see :ref:`helm_validator_topup` for Kubernetes or :ref:`compose_validator_topup` for Docker-compose.  Alternatively, Daml application code can use :ref:`AmuletRules_BuyMemberTraffic <type-splice-amuletrules-amuletrulesbuymembertraffic-66391>`
to increase the traffic credit balance.

Note that traffic credits are used whenever a confirmation request for a Daml
transaction is sent to the Global Synchronizer. The traffic is consumed even
if the confirmation request fails due to contention on consuming contracts.
For example, when submitting two ``Transfer`` operations that consume the same ``Amulet`` contracts.
