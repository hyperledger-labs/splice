..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _scratchnet:

==========================
How to Bootstrap a Network
==========================

.. todo:: adjust style of write-up below to the rest of docs

cometbft helm values
~~~~~~~~~~~~~~~~~~~~

1) disable state sync

::

     stateSync:
   -      enable: true
   -      rpcServers: "..."
   +      enable: false

2) For the single sv config, you are now bootstraping a single sv so put your own keys
   in:

::

     sv1:
   +    keyAddress: "..."
   +    nodeId: "..."
   +    publicKey: "..."

scan helm values
~~~~~~~~~~~~~~~~

add 1 value:

::

   +    isFirstSv: true

sv helm values
~~~~~~~~~~~~~~

1) remove joinWithKeyOnboarding

::

   -    joinWithKeyOnboarding:
   -      sponsorApiUrl: https://sv.sv-2.whatever.global.canton.network.digitalasset.com

2) Add initial helm values You may use these number values or choose
   other ones

::

   +    isDevNet: true
   +    onboardingType: found-dso
   +
   +    // see note below about the rest of these:
   +    initialSynchronizerFeesConfig:
   +      baseRateBurstAmount: 400000
   +      baseRateBurstWindowMins: 20
   +      extraTrafficPrice: 16.670000000000002
   +      minTopupAmount: 200000
   +      readVsWriteScalingFactor: 4
   +    onboardingFoundingSvRewardWeightBps: 10000

the note below if you plan to practice HDMs in this environment,
initialSynchronizerFeesConfig and onboardingFoundingSvRewardWeightBps
cannot be set when you are deploying in the phase with migrating=true.
Your SV app will fail to become healthy otherwise

3) Remove the ``decentralizedSynchronizerUrl`` config from ``sv-values.yaml``. It is only used for nodes that join after the initial SV.

other helm values you may need to consider
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

-  in all the places you use TARGET_CLUSTER / TARGET_HOSTNAME, you’ll
   want to point to your network’s base domain

   -  for example, we’re basing our network on
      hub-scratch.global.canton.network.digitalasset.com, so we set:

      -  targetCluster = hub-scratch
      -  targetHostname =
         hub-scratch.global.canton.network.digitalasset.com

-  if you use walletSweep, you probably won’t use it in this scratch
   environment unless you also plan to deploy a validator

Infra-level things you might want to consider
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

- Configure the IP allowlist to expose the cluster to an internal VPN
  or similar network you control. It is not recommended to expose it
  to the public internet at this point.

-  approved SV identities (which make their way into sv helm values): if
   you are deploying a single bootstraping node, you can set this to []

How to run through a HDM
~~~~~~~~~~~~~~~~~~~~~~~~

-  if your network is size 1 all your votes go through immediately, so
   you can schedule a HDM for like 5 minutes in the future

   -  New vote &gt; Set DSO Rules Configuration &gt; check “Set next scheduled
      synchronizer upgrade”

-  then you should be able to just run through the process as normal
