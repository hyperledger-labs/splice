..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0



.. NOTE: add your upcoming release notes below this line. They are included in the `release_notes.rst`.

.. release-notes:: Upcoming

    - Wallet UI

      - The wallet UI transaction hisory now uses the current amulet conversion rate to convert amounts instead of the historic one to
        reduce maintenace overhead.

    - Scan UI

      - The scan UI transaction hisory now uses the current amulet conversion rate to convert amounts instead of the historic one to
        reduce maintenace overhead.

    - Daml:

       - Restrict ``AmuletConfig`` to not allow fees as part of CIP FIXME. This has no functional effect
         as `CIP 78 <https://github.com/global-synchronizer-foundation/cips/blob/main/cip-0078/cip-0078.md>`_ set the fees to zero already.

         This also disables the choice ``AmuletRules_ComputeFees`` as it always returned 0. Application providers that statically
         link against ``splice-amulet`` will need to remove usages of this choice when recompiling against the new ``splice-amulet`` version.

    - Support 24h signing delays for token standard CC transfers and allocations

      - This change concern the CC implementation of the token standard APIs,
        so no change is required for clients of these APIs to make use of the
        new 24h submission delay. However, Scan returns a slightly different choice
        context, so make sure that your app passes that along opaquely.
      - As part of this change additional constraints are imposed on ``AmuletConfig``. All of these constraints
        are satisfied by the current configs on DevNet, TestNet and MainNet:

        - CC usage fees can no longer be set to non-zero values. They were set to zero in CIP 78.

        - ``extraFeaturedAppRewardAmount`` can no longer be set to a different value than ``featuredAppActivityMarkerAmount``.
          Both of those are currently set to $1.

        - The config schedule on ``AmuletRules`` can no longer contain ``futureValues``. The ability to do so through the UI was removed in CIP 51 but
          in theory it would have still been possible to set this through internal APIs.

    - ``TransferCommand`` is deprecated and will removed in a future
      version. It was originally introduced to support 24h signing
      delays and is no longer required now that this is also available
      through the token standard APIs. This also applies to the
      corresponding validator APIs
      ``/v0/admin/external-party/transfer-preapproval/prepare-send``
      and
      ``/v0/admin/external-party/transfer-preapproval/submit-send``
      which should be replaced by the :ref:`Token Standard APIs
      <token_standard>`.

    - FIXME: Add versions required for this change
