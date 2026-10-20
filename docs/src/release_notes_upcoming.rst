..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. NOTE: add your upcoming release notes below this line. They are included in the `release_notes.rst`.

.. release-notes:: Upcoming

  - Validator app

     - Support starting validator automation (e.g. minting delegations) for external parties that have keys embedded in a `PartyToParticipant` mapping
       but no dedicated `PartyToKeyMapping`.  This must be enabled through
       `canton.validator-apps.validator_backend.support-embedded-party-signing-keys = true`. In Splice 0.10.0 this will be the default.
       If you relied on not running automation for those parties it is recommended to instead make sure that the
       `ValidatorRight` contracts are [configured such that they won't start automation](https://docs.canton.network/global-synchronizer/production-operations/scalability#bypassing-the-limit).
