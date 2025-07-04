..
   Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

Overview
========

Below is a high-level summary of the key concepts and technology relevant to operating a Validator. This summary focuses on what matters for validator operation, the benefits from the token economics, and the underlying technology.

Role of a Validator
-----------------------------

* **Definition & Function:**
A Validator in the Global Synchronizer ecosystem primary roles are to:
        * **Validate transactions:** Process and validate both Canton application transactions (built using Daml) and Canton Coin transfers.
        * **Record activity:** Create “activity records” (for example, for coin usage and uptime) that later form the basis for minting rewards.
        * **Facilitate network connectivity:** Serve as an endpoint for users (via their participant nodes) connecting to the decentralized Global Synchronizer (the infrastructure layer that orders and delivers messages among nodes).
        * **Coordinate upgrades and migrations:** Automates adoption of Daml model upgrades as required by the operators of the Global Synchronizer, and supports major version upgrades of the underlying Canton protocol.

* **Operational context:**
Validators work within the broader Canton Network—a “network of networks” where each node stores only the data it needs (thanks to Daml’s privacy model) and synchronizes with other Validators (and non-Validator Participant nodes) via synchronizers. Validators typically connect to one or more of these synchronizers (which might be run as centralized or decentralized services) to receive and confirm encrypted messages.

Validator Operation & Underlying Technology
-----------------------------

* **Daml Ledger Model & Privacy:** The system is built on the Daml smart contract language and ledger model.
        * **Hierarchical Transactions:** Transactions are composed of sub-transactions. Only the parties to the sub-transactions (and therefore the validators serving them) are authorized to see the relevant nodes of each transaction tree.
        * **Privacy by Design:** Validators only process the data relevant to their hosted users, preserving privacy while still participating in a global, virtual ledger.

* **Synchronizers and Message Ordering:**
        * Validators connect to synchronizers (aka Canton Service Providers, CSPs) to get a total order of encrypted messages.
        * The Global Synchronizer (deployed by a set of independent Super Validators) is a decentralized instance of a Canton synchronizer. It uses a Byzantine Fault Tolerant (BFT) consensus protocol to sequence transactions, ensuring that all validators receive a consistent ordering for any part of the ledger they see.

* **Confirmation Protocol and Message Ordering:**
         * When a transaction is initiated, it is structured into a tree, where each node in the tree represents a subtransaction in the overall atomic transaction. This means the transaction is “chopped” into pieces based on Daml’s privacy rules
         * Each validator receives only the portion of the transaction tree that the parties hosted on that Validator will verify.
         * A two-step confirmation process (a two-phase atomic commit with rollback) is used to both lock the involved contracts and ensure that all required parties have confirmed the transaction before it is finalized.

* **Resource and Traffic Management:**
         * Validators must manage bandwidth and storage usage since each transaction synchronized on the Global Synchronizer, e.g. Canton Coin transfers is associated with resource usage fees (e.g., base transfer fees, coin locking fees, holding fees, and traffic fees).
         * To create new traffic balances (expressed in megabytes), Validators burn Canton Coin and send proof of the burn to Super Validators, who then update the traffic balance for the Validator. This ensures that transaction sequencing and message delivery remain efficient.

Token Economics & Minting Incentives
------------------------------

* **Burnt-Mint Equilibrium:**
         * **Fee Burninng:**  Users pay fees (denominated in USD but paid by burning Canton Coin) when they initiate Canton Coin transfers or when they create a traffic balance. Instead of paying these fees to a central authority, the coins are burned—i.e., removed from circulation.
         * **Minting Rewards:** Validators (as well as Super Validators and application providers) can mint new Canton Coins in return for their “utility” contributions:
            * **Infrastructure Operation:** Super Validators operating synchronizer nodes earn minting rights by contributing to the synchronization service.
            * **Application Services:** Application providers can earn rewards any time they facilitate a transaction.
            * **Coin Usage:** When a user’s transfer (or coin burning) takes place via a Validator’s node, that Validator earns “minting rights” proportional to the fees (activity record weight) generated.
            * **Liveness Incentives:** Validators are rewarded for uptime and for being ready to serve transaction traffic. If a Validator does not use all its minting allowance via direct activity, a portion is allocated as a “liveness” bonus.
         * **Dynamic Equilibrium:** The system is designed so that, over the long term, the total amount of coins burned (which reflects actual network utility) roughly balances the coins minted (subject to a predetermined maximum allowed minting curve). When usage is high, more coins are burned, tending to increase the token’s conversion rate; when usage is lower, supply increases until balance is restored.

* **Minting Curve and Allocation:**
        * The minting curve specifies both the total number of Canton Coins that can be minted in each period (round) and the split among stakeholder groups.
        * **Distribution Split:** The total mintable supply is shared between infrastructure providers (validators and super validators) and application providers. Early on, Super Validators (who operate both synchronization and validator services) earn a higher share; over time, the application provider and validator pools grow relative to the super validator pool.
        * **Caps and Featured Applications:**
           * There are limits (“caps”) on how much a Validator or App can mint per unit of “activity record weight.”
           * These measures are in place to prevent gaming the system.
           * Some applications can be “featured” by a vote of Super Validators, which raises minting caps for their associated activity.
* **Fee Structure Details:**
         * **Transfer Fees:**
            * A small percentage fee is applied to transfers (with regressive tiers so that higher-value transactions incur lower percentage fees).
         * **Resource Usage Fee:**
            * These fees cover the cost of network resources and include a base fee per output coin, coin locking fees, holding fees (to incentivize merging coins), and synchronizer traffic fees.
         * **Fee conversion:**
            * The conversion between USD-denominated fees and Canton Coin is updated every minting cycle, with the conversion rate determined on-chain by Super Validators.

Benefits and Practical Considerations for Node Operators
-----------------------------

*  **Direct Financial Incentives:**
         * As a Validator operator, you earn Canton Coins by processing transactions. Your rewards come from
            * Minting for facilitating coin transfers (coin usage minting).
            * Liveness rewards for uptime and responsiveness.
         * As an Application Provider, you earn Canton Coins by processing transactions using the Global Synchronizer. Over time, minting by Application Providers approaches 50% of the total Canton Coin supply.
         * Over time, as the network usage increases (and fees burned increase), the validator’s ability to mint more coins may provide a competitive economic incentive.

* **Scalability and Efficiency:**
         * Validators process only the subset of the ledger relevant to their hosted users. This horizontal scalability means that your node can operate efficiently without having to store or validate every transaction on the network.
         * The use of multiple synchronizers (and the ability to connect to one or more centralized or decentralized synchronizers) reduces network bottlenecks and allows you to choose the infrastructure that best meets your latency, throughput, and trust requirements.

* **Operational Flexibility:**
         * Validators can operate either as independent node operators (hosting their own participant node) or as part of a broader infrastructure offering.
         * The system’s architecture and fee structure offer optionality: you may choose to prepay network traffic using Canton Coin or negotiate arrangements (for example, with third-party service providers) that suit your operational profile.

In Summary
-----------------------------

A Validator is not just a passive participant; it is an active contributor to both the integrity and the economic dynamics of the Canton Network. By:

* **Validating transactions** in a privacy-first, Daml-based ledger,
* **Connecting to and synchronizing with decentralized synchronizers** using BFT protocols,
* **Recording activity and facilitating fee burns** that underlie the Burn-Mint Equilibrium mechanism,
* And **earning new coins based on actual network utility and uptime,**

You, as a node operator, play a central role in maintaining network consistency, security, and scalability while also benefiting from the token economics designed to reward real-world utility.

This synthesis should give you a clear overview of the technology stack and economic incentives tied to operating a Validator. If you need more details on any particular mechanism (such as fee calculations, activity record structure, or minting rounds), the following white papers provides further technical specifications:

* `Canton Network <https://www.digitalasset.com/hubfs/Canton/Canton%20Network%20-%20White%20Paper.pdf>`_
* `Canton Coin <https://www.digitalasset.com/hubfs/Canton%20Network%20Files/Documents%20(whitepapers%2c%20etc...)/Canton%20Coin_%20A%20Canton-Network-native%20payment%20application.pdf>`_







.. todo:: add overview of the deployment docs explaining

   - difference between SV and validator nodes
   - docker compose vs. helm based deployments
   - available networks and releases and their difference

  Consider inlining these into the SV and validator node docs
