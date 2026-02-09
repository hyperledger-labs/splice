..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0



.. NOTE: add your upcoming release notes below this line. They are included in the `release_notes.rst`.

release-notes:: Upcoming

- Daml

  - Optimize the number of views in the automation run by the SV app to convert ``FeaturedAppActivityMarker`` contracts into ``AppRewardCoupon`` contracts.

    This requires a Daml upgrade to

      ================== =======
      name               version
      ================== =======
      amulet             0.1.16
      amuletNameService  0.1.17
      dsoGovernance      0.1.22
      splitwell          0.1.16
      validatorLifecycle 0.1.6
      wallet             0.1.17
      walletPayments     0.1.16
      ================== =======
