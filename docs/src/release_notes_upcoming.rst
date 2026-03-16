..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0



.. NOTE: add your upcoming release notes below this line. They are included in the `release_notes.rst`.

.. release-notes:: Upcoming

    .. important::

       The validator APIs ``/v0/admin/external-party/transfer-preapproval/prepare-send``
       and ``/v0/admin/external-party/transfer-preapproval/submit-send`` are deprecated and will be removed in a future version.
       Replace any usages you  by the :ref:`Token Standard APIs <token_standard>`.

    - Validator app

       - The endpoints
         ``/v0/admin/external-party/transfer-preapproval/prepare-send``
         and
         ``/v0/admin/external-party/transfer-preapproval/submit-send``
         are deprecated and will be removed in a future version. Use the token standard APIs for initiating transfers instead.

       - Fix a bug that caused the Validator App to fail during restarts when the Scan Apps defined
         in ``scanClient.seedUrls`` were unavailable. This fix ensures the Validator App uses its
         persisted scan connections from previous runs, removing the dependency on seedUrls
         scan availability for successful reboots.

    - Daml:

      .. important::

          **Action recommended from app devs:**

          **App devs whose app's Daml code statically depends on** ``splice-amulet < 0.1.17`` should recompile their Daml code
          to link against ``splice-amulet >= 0.1.17`` in order to be ready to consume new field ``contractStateSchemaVersion`` added to ``AmuletRules``.

          This is required because once the new fields are set, downgrades of `AmuletRules` will fail.
          Note that the field will not be set automatically after this upgrade.
          It is strongly recommended to avoid direct dependencies on amulet and replace them by dependencies through token standard interfaces.

          No change is required for apps that build against the :ref:`token_standard`
          or :ref:`featured_app_activity_markers_api`.

       - Restrict ``AmuletConfig`` to not allow fees as part of `CIP 107 <https://github.com/canton-foundation/cips/blob/main/cip-0107/cip-0107.md>`_.
         This has no functional effect as `CIP 78 <https://github.com/global-synchronizer-foundation/cips/blob/main/cip-0078/cip-0078.md>`_ set the fees to zero already.

         This also disables the choice ``AmuletRules_ComputeFees`` as it always returned 0. Application providers that statically
         link against ``splice-amulet`` will need to remove usages of this choice when recompiling against the new ``splice-amulet`` version.

       - Support 24h signing delays for token standard CC transfers and allocations, see `CIP 107 <https://github.com/canton-foundation/cips/blob/main/cip-0107/cip-0107.md>`_.

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

         - This does change transaction structure, in particular, ``AmuletRules_Transfer`` is no longer a child node of the token standard operations and some other choices.
           Token standard compliant history parsing should not require adjustments. However apps and wallets that parse the Splice choices directly may need to be adjusted.

         - The check that the lock on a locked amulet expires before the underlying amulet
           expires has been removed.

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
