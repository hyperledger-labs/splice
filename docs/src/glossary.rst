..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

Glossary
========

.. glossary::

    ACS

      :term:`Active Contract Set`

    Active Contract Set

      The set of contracts that are currently active on the ledger as per the view of a given participant.

    Amulet

      The generic name for the code and the logic implementing Canton Coin.

    Amulet Name Service

      The generic name for the code and logic implementing the Canton Name Service.

    CC

      :term:`Canton Coin`

    CN

      :term:`Canton Network`

    Canton Network

      A network of multi-party business processes operated by
      business entities in the form of CN applications.

    CN Application

      A set of Canton participant and domain
      nodes and supporting code operated by a single business entity for the
      purpose of providing access to a particular multi-party business process
      to other entities on the Canton network.

    BFT

      Byzantine Fault Tolerance.
      A property of a distributed system that allows it to continue operating correctly
      in the presence of a certain number of faulty nodes.
      Typically ``f`` denotes the number of faulty nodes that the system can tolerate.
      Across all layers of Splice, ``f`` corresponds to slighly less than 1/3 of the total number of SVs
      (``floor((n-1)/3)``, to be exact).

    CN Global Synchronizer

      * global synchronization domain
      * can host small apps directly on domain
      * acts as shared synchronization domain to intermediate between different apps' domains
      * run by super validator collective with BFT
      * domain usage costs :term:`synchronizer fees` which are paid in Canton Coin by the
        operator of each validator

    Canton Coin

      * currency issued by super validator collective
      * used for :term:`synchronizer fees`
      * coins accrue holding fees that pay for the coin's usage of DSO storage space
      * all CC transactions are public
      * supports locked coins that can be unlocked by lock holder
      * support transfers single sender, multi-receiver transfers
      * transfers cost admin fees and produce app reward for receiver and
        validator reward for validator that hosts sender
      * transfers are associated to mining rounds
      * rewards can be collected in next mining round

    CN Validator

      * One node in the CN
      * consists of canton participant, validator app, wallet app
      * validator app for admin operations by the validator operator like
        user/party management

    CN Supervalidator

      * One node in the CN
      * In addition to CN Validator components, also consists of canton sequencer, canton mediator, sv app and scan app
      * sv app for admin operations of the :term:`CN Global Synchronizer`
      * scan app for providing publicly visible data

    CN Wallet

      * provides payment APIs for other apps to build upon ("pay with CC") and corresponding UI, e.g., approve payment
      * used by CN users to manage their CC holdings & reward collection
      * provides UI for managing peer-to-peer transfers
        between two users

    Canton Name Service (sometimes also called directory service)

      * allows parties to buy a globally unique, human readable name for a time period mapped to their party (similar to DNS)
      * allows each party to declare one of their entries as the primary
        entry which is used to provide a human readable name to their party
        (similar to reverse DNS)
      * provides APIs for resolution in both directions that can be used by
        other apps (e.g., the wallet) to display and accept CNS names instead of party ids

    Global Synchronizer Foundation
      * Foundation charged with fostering the development and growth of the
        Global Synchronizer in the Canton Network, and facilitate its
        governance, see https://sync.global/.

    GSF

      * abbreviation for :term:`Global Synchronizer Foundation`

    Splice

      * The name of the HyperLedger lab project that will host the code for Amulet, DSO governance,
        Amulet Name Service, SV nodes, and validator nodes.

    Synchronizer fees

      :ref:`traffic`
