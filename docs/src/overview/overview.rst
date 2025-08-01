..
   Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

Global Synchronizer for the Canton Network
===========================================

Canton Network
###########################

The `Canton Network <https://www.canton.network>`__ is the first privacy-enabled interoperable blockchain network, designed for regulated, real-world assets.
Blockchain applications in the Canton Network can use the Global Synchronizer to enable atomic transactions across sovereign blockchains, without sacrificing privacy or control.

- The `Global Synchronizer <https://www.canton.network/global-synchronizer>`__ is a decentralized and transparently governed interoperability service for the Canton Network.
- Its native token, Canton Coin, is a utility token launched as part of the Global Synchronizer. Designed to overcome enterprise adoption barriers of other networks and be compatible with an ecosystem used by regulated financial enterprises.

If you need more details on any particular mechanism (such as fee calculations, activity record structure, or minting rounds), the following white papers provides further technical specifications: `Canton Network white paper <https://www.digitalasset.com/hubfs/Canton/Canton%20Network%20-%20White%20Paper.pdf>`_, `Canton Coin white paper <https://www.digitalasset.com/hubfs/Canton%20Network%20Files/Documents%20(whitepapers%2c%20etc...)/Canton%20Coin_%20A%20Canton-Network-native%20payment%20application.pdf>`_.

Global Synchronizer
###########################

The Global Synchronizer is a decentrally operated service, using a 2/3 majority Byzantine Fault Tolerant (:term:`BFT`) consensus protocol for message ordering and confirmation, and BFT majority voting on governance changes.

Its infrastructure is operated by independently acting organizations that run components of the decentralized infrastructure called Super Validators, that coordinate activities via an on-chain governance application.
Its open source code is maintained in `Splice <https://github.com/hyperledger-labs/splice>`__.

The `Global Synchronizer Foundation ("GSF") <https://sync.global>`__ has been created in partnership with the Linux Foundation to coordinate the governance of the Global Synchronizer and lead efforts in growing the Global Synchronizer ecosystem.
The GSF provides transparency into Super Validator operations and has a node in the Global Synchronizer to take part in votes on behalf of its members.

Super Validators
###########################

Super Validators form the backbone of the Global Synchronizer's decentralized interoperability and synchronization infrastructure. They ensure the integrity, security, and operational reliability of the Global Synchronizer by:

- Running the core infrastructure of the Global Synchronizer
- Sequencing transactions across the network
- Validating Canton Coin transactions
- Participating in network governance and decision-making

Validators
###########################

Validators work within the broader Canton Network—a “network of networks” where each node stores only the data it needs and synchronizes with other Validators via synchronizers. Validators typically connect to one or more of these synchronizers (which might be run as centralized or decentralized services) to receive and confirm encrypted messages.

A Validator in the Global Synchronizer ecosystem primary roles are to:

* Validate transactions
* Record activity
* Facilitate network connectivity
* Coordinate upgrades and migrations



Tokenomics
###########################

`Canton Coin <https://www.digitalasset.com/hubfs/Canton%20Network%20Files/Documents%20(whitepapers%2c%20etc...)/Canton%20Coin_%20A%20Canton-Network-native%20payment%20application.pdf>`_ incentivizes application builders, users and infrastructure providers to use the Global Synchronizer.
The Canton Coin application employs a burn-mint equilibrium mechanism, aiming to stabilize the conversion rate of Canton Coin around the intrinsic value it provides to network users:

- Fee Burninng: Users pay fees (denominated in USD but paid by burning Canton Coin) when they initiate Canton Coin transfers or when they create a traffic balance. Instead of paying these fees to a central authority, the coins are burned—i.e., removed from circulation.
- Minting Rewards: Validators (as well as Super Validators and application providers) can mint new Canton Coins in return for their “utility” contributions:

  - Infrastructure Operation: Super Validators operating synchronizer nodes earn minting rights by contributing to the synchronization service.
  - Application Services: Application providers can earn rewards any time they facilitate a transaction.
  - Usage: When a Validator uses the network, that Validator earns “minting rights” proportional to activity generated.
  - Liveness Incentives: Validators are rewarded for uptime and for being ready to serve transaction traffic. If a Validator does not use all its minting allowance via direct activity, a portion is allocated as a “liveness” bonus.

- Dynamic Equilibrium: The system is designed so that, over the long term, the total amount of coins burned (which reflects actual network utility) roughly balances the coins minted (subject to a predetermined maximum allowed minting curve). When usage is high, more coins are burned, tending to increase the token’s conversion rate; when usage is lower, supply increases until balance is restored.
