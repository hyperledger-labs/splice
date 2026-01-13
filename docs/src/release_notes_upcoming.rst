..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0



.. NOTE: add your upcoming release notes below this line. They are included in the `release_notes.rst`.

.. release-notes:: Upcoming

- Daml:

   - Restrict ``AmuletConfig`` to not allow fees as part of CIP FIXME. This has no functional effect
     as `CIP 78 <https://github.com/global-synchronizer-foundation/cips/blob/main/cip-0078/cip-0078.md>`_ set the fees to zero already.

     This also disables the choice ``AmuletRules_ComputeFees`` as it always returned 0. Application providers that statically
     link against ``splice-amulet`` will need to remove usages of this choice when recompiling against the new ``splice-amulet`` version.
