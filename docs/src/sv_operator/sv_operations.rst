..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _sv_operations:

SV Operations
=============

These sections give an overview of some of the functionalities enabled through your SV node
as well as general information useful to SV node operators.


.. _sv_security_notice:

Security Notice
---------------

.. warning::

  As an SV operator, you are most welcome to review, install, and use third-party Daml apps provided you
  **install third-party Daml apps on a validator node separate from your SV node**.

  Installing additional Daml apps on an SV node is not supported and may compromise its secure
  operations. In particular, please refrain from manually uploading additional ``.dar`` files
  to your SV node or manually connecting it to third-party synchronizers.


.. _generate_onboarding_secret:

Generate a validator onboarding secret
--------------------------------------

If you want to onboard a new validator, you can obtain a one-time use onboarding secret directly from the SV web UI.

The onboarding secret expires after 48 hours.

To generate this key you need to :ref:`login into the SV web UI <local-sv-web-ui>` and navigate to the `Validator Onboarding` tab. In that tab, click on the button to create the secret
and copy the last generated `onboarding secret`.


.. image:: images/create-onboarding-secret.png
  :width: 600
  :alt: Generate an onboarding secret in the SV UI.

Using the secret, the validator operator can follow :ref:`this instruction to prepare for validator onboarding <validator-onboarding>`.

.. _sv-identities-overview:

Identities used by SV nodes on different layers
-----------------------------------------------

This section gives a brief overview over some of the most important identities associated with a single SV node,
and for each type of identity: its role and relevance for quorums (if any), under what circumstance it can be reused,
and the implications of losing it (e.g., by loss or compromise of relevant cryptographic keys).

Each identity is relevant on different layers, or within different subsystems, of the Canton & Global Synchronizer software stack.
We strive to reduce coupling between layers, so that in general it is possible to rotate identities independently of each other.

SV identity
+++++++++++

- Used for identifying your node *before* it was onboarded to the system. See :ref:`sv-identity`.

- Established SVs will confirm onboarding requests by SVs that can authenticate themselves with regards to an approved SV identity.

- An SV identity can be reused for multiple onboardings, as long as it's approved by a quorum of established SVs and,
  for non-DevNet clusters, no SV with the same name is already onboarded.

- An operator that has lost control over their SV identity will not be able to onboard SVs
  until a quorum of established SV operators have approved a new identity under the control of that operator.

Participant identities
++++++++++++++++++++++

- Used for securing Daml workflows and for determining an SV's ``svPartyId``,
  which is used for various DSO governance flows as well as for receiving SV rewards.
  For general information on Canton and Daml identities see the `Canton documentation on Identity Management <https://docs.daml.com/canton/usermanual/identity_management.html>`_.
- Participant identities are important for multiple types of quorums

  - Quorums for confirming Daml transactions as the DSO party (>⅓ of onboarded SVs)
  - Quorums for administering the domain topology on behalf of the DSO party (>⅔ of onboarded SVs once activated)
  - Quorums for confirmation-based DSO Daml workflows (>⅔ of onboarded SVs; on DevNet instead usually >½)

  .. TODO(#7746): remove "once activated"

- In general, participant identities *can't* be reused on the same global domain,
  i.e., without the network being reset/redeployed.

- Loss of control over a participant namespace implies loss of control over the coin balances for all parties hosted under that namespace.
  On a network level, loss of a participant namespace constitutes an SV failure and reduces the overall fault tolerance buffer,
  until the respective SV is offboarded from the DSO.

CometBFT node identities
++++++++++++++++++++++++

- Used within the CometBFT network spanned by SVs for operating the global domain.
  See :ref:`cometbft-identity`.

- The CometBFT validator key is used in CometBFT quorums (>⅔ of SVs),
  which are required for advancing the CometBFT blockchain and changing the CometBFT configuration and validator set.

- Reusing CometBFT node identities can cause (transient) instability in the CometBFT network and is therefore not recommended.

- Compromise of CometBFT key material constitutes an SV failure and reduces the overall fault tolerance buffer.
  For recovering, it is sufficient for an SV operator to set up a new CometBFT node (with a fresh identity)
  and make sure that it is correctly registered by their SV app backend
  (e.g., by amending the configuration of their SV app backend and restarting it).


Updating the reward weight of an SV
-----------------------------------

The following steps are required to update the reward weight of an SV:

#. Receive from the SV owners an agreed-upon update to the SV weights.

#. If the SV whose weight is being adjusted defined `extraBeneficiaries` (as described in :ref:`sv-helm`),
   they will have to update them accordingly. Namely:

   - On weight increases they should add a new entry with the extra weight, as otherwise any leftovers will go to the SV party.

   - On weight decreases, the last extra beneficiary will have their weight capped.

#. Note that you can and usually should update the extra-beneficiaries config before the weight change takes effect.
   The extra entries at the end will just be ignored.

#. Start a governance vote. To do so, create a new vote request in the SV web UI under the `Governance` tab.
   Select the "Update SV Reward Weight" name, then select the SV member and specify the new reward weight.

#. Wait for the vote request to get approved and executed.
   Once that happens, the DSO info tab will show the updated reward weight,
   and the SV will earn rewards according to the new weight moving forward.

#. Make sure that the changed reward is also reflected on `the configs repository <https://github.com/global-synchronizer-foundation/configs>`_.
   Otherwise, the old value would become effective again in the event of an onboarding and reonboarding.
