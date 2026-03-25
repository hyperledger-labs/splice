..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0



.. NOTE: add your upcoming release notes below this line. They are included in the `release_notes.rst`.

.. release-notes:: Upcoming

    - SV and Validator app

    - Going forward unusable splice DARs will be automatically unvetted by the super validators.
      This will be used for DARs that can already not be used,
      e.g., because a downgrade of AmuletRules to that version is not possible so it does not force more aggressive upgrades for validators or app devs.

      The minimum supported versions are:

         ================== =======
         name               version
         ================== =======
         amulet             0.1.14
         amuletNameService  0.1.14
         dsoGovernance      0.1.19
         validatorLifecycle 0.1.5
         wallet             0.1.14
         walletPayments     0.1.14
         ================== =======

    - Scan

       - Added a new ``/v1/domains/{domain_id}/parties/{party_id}/participant-id`` endpoint that returns all participant IDs hosting a given party,
         supporting parties hosted on multiple participants. The previous ``/v0`` endpoint only supported single-participant hosting.

    - SV UI

       - Fixed the SV UI to correctly handle parties hosted on multiple participants (e.g., the DSO party).
         The SV app now proxies the party-to-participant mapping through Scan's new v1 endpoint.

    - LocalNet

       - LocalNet now supports running multiple synchronizers side by side for testing multi-synchronizer scenarios. By default, only the ``global``
         synchronizer is active. To enable the second synchronizer called ``app-synchronizer``, start LocalNet with the ``multi-sync`` Docker
         Compose profile (``--profile multi-sync``). The ``app-provider`` and ``app-user`` participant nodes are cross-connected to both
         synchronizers. See :ref:`multi-sync-localnet` for details.
