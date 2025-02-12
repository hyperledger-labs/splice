..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

Required Network Parameters
+++++++++++++++++++++++++++

To initialize your validator node, you need the following parameters
that define the network you're onboarding to and the secret
required for doing so.


MIGRATION_ID
    The current migration id of the network (dev/test/mainnet) you are trying to connect to. You can find this on https://sync.global/sv-network/.

SPONSOR_SV_URL
    The URL of the SV app of your SV sponsor. This should be of the form |generic_sv_url|, e.g., if the Global Synchronizer Foundation is your sponsor use |gsf_sv_url|.

ONBOARDING_SECRET
   The onboarding secret provided by your sponsor. If you don't already have one, ask your sponsor. Note that onboarding secrets are one-time use and expire after 48 hours.
   If you don't join before it expires, you need to request a new secret from your SV sponsor.

   .. admonition:: DevNet-only

     On DevNet, you can obtain an onboarding secret automatically by
     calling the following endpoint on any SV (replace ``SPONSOR_SV_URL`` with the URL of your SV sponsor):

     .. parsed-literal::

        curl -X POST SPONSOR_SV_URL/api/sv/v0/devnet/onboard/validator/prepare

     Note that this self-served secret is only valid for 1 hour.

