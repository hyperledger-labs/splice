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

