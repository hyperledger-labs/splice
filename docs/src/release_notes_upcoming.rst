..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0



.. NOTE: add your upcoming release notes below this line. They are included in the `release_notes.rst`.

.. release-notes:: Upcoming

    - Daml:

       - Restrict ``AmuletConfig`` to not allow fees as part of `CIP 107 <https://github.com/canton-foundation/cips/blob/main/cip-0107/cip-0107.md>`_.
         This has no functional effect as `CIP 78 <https://github.com/global-synchronizer-foundation/cips/blob/main/cip-0078/cip-0078.md>`_ set the fees to zero already.

         This also disables the choice ``AmuletRules_ComputeFees`` as it always returned 0. Application providers that statically
         link against ``splice-amulet`` will need to remove usages of this choice when recompiling against the new ``splice-amulet`` version.

         These Daml changes require an upgrade to the following Daml versions **before**
         voting to set the transfer fees to zero:

         ================== =======
         name               version
         ================== =======
         amulet             0.1.17
         amuletNameService  0.1.18
         dsoGovernance      0.1.23
         validatorLifecycle 0.1.6
         wallet             0.1.18
         walletPayments     0.1.17
         ================== =======
