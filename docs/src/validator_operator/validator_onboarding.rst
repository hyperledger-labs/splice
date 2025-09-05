..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _validator_onboarding_process:

Validator Onboarding Process
============================

Networks
--------

There are three different networks: DevNet, TestNet and MainNet. If you are
only interested in setting up a validator node, you can set up a node
on DevNet for practice, then jump to MainNet. If you’d like to build,
test, or deploy an app, we recommend that you run a node on DevNet,
TestNet, and MainNet.

DevNet
    DevNet is open to any node but still requires the validator’s egress IP to be added to the allowlist maintained by the SV node operators.
    It gets reset every 3 months.
    DevNet always gets upgraded to new versions before TestNet and MainNet, so it is a good way to test upgrades of your node
    and their impact on your apps before they reach MainNet.
TestNet
    Joining TestNet requires that you have been approved to join MainNet by
    the Tokenomics Committee of the Global Synchronizer Foundation.
    You can initiate a request to do so through https://sync.global/validator-request/.
    Just like for DevNet, is also still requires your validator’s egress IP to be added to the allowlist
    and it requires an onboarding secret from your SV sponsor.
    Just like DevNet, TestNet gets reset every 3-6 months,
    but the reset schedule is shifted so TestNet never gets reset at the same time as DevNet.
    TestNet upgrades to new versions after DevNet, but before MainNet so it provides an additional layer of testing.
    Application providers are encouraged to maintain long-running instances of their app on TestNet,
    which allows using TestNet to test composed applications.
MainNet
    MainNet requires everything TestNet requires, so approval by the Tokenomics committee,
    an IP on the allowlist, and an onboarding secret from your SV sponsor. MainNet never gets reset
    and is therefore the only network where data is always preserved.
    MainNet gets upgraded to new versions after DevNet and TestNet.


Onboarding Process Overview
---------------------------

Onboarding a Validator involves the following steps (for each network you want to join).

1. Provide your sponsoring SV with the egress IP for your Validator node.
   Only one IP may be provided per network, and this IP must be distinct from the IP you use for any other of the three networks.

   .. note::
      At this point this can also be accomplished by connecting your validator through a VPN run by an SV
      This can be useful when trying to run a validator from a local laptop.
      This option may be removed in the future.
2. Wait for super validators to adopt the new IP allowlist. This usually takes between 2-7 days.
3. If you want to access the Canton Coin Scan Web UI from your laptop, you also need to ensure that
   you can connect to a VPN operated by one of the SVs. This is required as laptops usually
   do not have static IP addresses and the Scan web UI is not (yet) fully public. If you can
   use your validator egress IP also for browsing the web UI this is not necessary.
4. Request an onboarding secret from your SV sponsor. On DevNet, you
   can do this yourself through an API call
   (refer to :ref:`Deployment instructions <validator_operator>` for details).
   On TestNet and MainNet your SV sponsor needs to provide you with this manually.
   Note that onboarding secrets are only valid for 48 hours and are one-time use, and self-generated
   DevNet secrets are only valid for 1 hour. If it expired, you need to request a new one.
5. Deploy your node either using docker compose or Kubernetes. Refer
   to the :ref:`Deployment Options <validator_operator>` for
   information on how to choose between them and references to each of
   the two approaches. You will need to make sure that all IP traffic going from your validator to the SVs
   uses the egress IP you provided to your SV sponsor and you need to provide the onboarding secret.

Validating that your IP has been approved
-----------------------------------------

To validate that the SVs have added you to their respective IP
allowlists, you can query their Scan URLs. Note that this must be run
from the same egress IP from which you want to deploy your validator,
e.g., from the VM that you want to run your docker compose setup on,
or from within your Kubernetes cluster.

First, please confirm that your egress IP in the terminal in which you are
running the command is indeed the one you provided for whitelisting by running:

.. parsed-literal::

   curl -sSL http://checkip.amazonaws.com

and confirming that the IP matches what you have provided for whitelisting. If it does,
run the following command to check to which instance of Scan you can connect.

Note that the following snippet requires installing `jq <https://jqlang.org/>`_.

.. parsed-literal::

   (set -o pipefail
   CURL='curl -fsS -m 5 --connect-timeout 5'
   for url in $($CURL |gsf_scan_url|/api/scan/v0/scans | jq -r '.scans[].scans[].publicUrl'); do
     echo -n "$url: "
     $CURL "$url"/api/scan/version | jq -r '.version'
   done)

You should see output in the form shown below, where each line indicates one SV and the version it is on. If you see timeouts that SV has not yet added you to their allowlist,
if you do not get any errors, then all SVs have added you. Note that the URLs and versions will vary over time so don't try to compare exactly.

.. code-block:: bash

   https://scan.sv-2.test.global.canton.network.digitalasset.com: 0.3.6
   https://scan.sv.test.global.canton.network.tradeweb.com: 0.3.6
   https://scan.sv-1.test.global.canton.network.cumberland.io: 0.3.6
   https://scan.sv-1.test.global.canton.network.orb1lp.mpch.io: 0.3.6
   https://scan.sv-1.test.global.canton.network.sync.global: 0.3.6
   https://scan.sv.test.global.canton.network.sv-nodeops.com: 0.3.6
   https://scan.sv-1.test.global.canton.network.mpch.io: 0.3.6
   https://scan.sv-2.test.global.canton.network.cumberland.io: 0.3.6
   https://scan.sv-1.test.global.canton.network.c7.digital: 0.3.6
   https://scan.sv-1.test.global.canton.network.digitalasset.com: 0.3.6

Apart from connectivity to Scan, your validator must also be able to connect to the sequencer endpoints of the SVs.
If you are encountering issues related to connecting to the synchronizer,
you can use the following snippet to confirm that you are able to reach those endpoints
(i.e., that SVs have whitelisted your IP for those endpoints as well).
Note that the following snippet requires installing `jq <https://jqlang.org/>`_ and `grpcurl <https://github.com/fullstorydev/grpcurl>`_.

.. parsed-literal::

   (set -o pipefail
   for url in $(curl -fsS -m 5 --connect-timeout 5 |gsf_scan_url|/api/scan/v0/dso-sequencers | jq -r '.domainSequencers[].sequencers[].url | sub("https://"; "")'); do
     echo -n "$url: "
     grpcurl --max-time 10 "$url":443 grpc.health.v1.Health/Check
   done)

Sequencers that are functional and have whitelisted your IP correctly will return ``"status": "SERVING"`` in the ``grpcurl`` output.

.. code-block:: bash

   sequencer-1.sv-2.test.global.canton.network.digitalasset.com: {
     "status": "SERVING"
   }
   sequencer-1.sv.test.global.canton.network.tradeweb.com: {
     "status": "SERVING"
   }
   sequencer-1.sv-1.test.global.canton.network.cumberland.io: {
     "status": "SERVING"
   }
   sequencer-1.sv-1.test.global.canton.network.orb1lp.mpch.io: {
     "status": "SERVING"
   }
   sequencer-1.sv-1.test.global.canton.network.sync.global: {
     "status": "SERVING"
   }
   sequencer-1.sv.test.global.canton.network.sv-nodeops.com: {
     "status": "SERVING"
   }
   sequencer-1.sv-1.test.global.canton.network.mpch.io: {
     "status": "SERVING"
   }
   sequencer-1.sv-2.test.global.canton.network.cumberland.io: {
     "status": "SERVING"
   }
   sequencer-1.sv-1.test.global.canton.network.c7.digital: {
     "status": "SERVING"
   }
   sequencer-1.sv-1.test.global.canton.network.digitalasset.com: {
     "status": "SERVING"
   }

The default configuration for both of these requires access to at least 2/3 of the SVs for each of scans and sequencers.
You may, at your option and own risk, configure connection to a single trusted scan and sequencer as described under :ref:`validator helm chart configuration <helm-validator-install>`, at the cost of losing BFT integrity guarantees.

Stay Connected
--------------

To stay connected with other validator operators, there is a shared slack channel and a few mailing lists:

Slack
~~~~~

Join the ``#validator-operations`` channel hosted by the :term:`Global Synchronizer Foundation` using Slack Connect:
https://daholdings.slack.com/archives/C08AP9QR7K4. Your Slack workspace may allow you to browse to this channel, or you can ask your SV sponsor to send you an invitation.

Mailing Lists
~~~~~~~~~~~~~

You can sign up for various mailing lists provided by the :term:`Global Synchronizer Foundation`. To do so, first create an account at https://groups.io/ and then log in at https://lists.sync.global/.
We recommend the following lists:

* `main <https://lists.sync.global/g/main/messages>`_: for overall information about the Canton Network.
* `cip announce <https://lists.sync.global/g/cip-announce/messages>`_: for new Canton Improvement Proposals (CIPs).
* `tokenomics-announce <https://lists.sync.global/g/tokenomics-announce/messages>`_: for announcements from the Tokenomics commitee. This also includes approval of new validators.
* `validator-announce <https://lists.sync.global/g/validator-announce/messages>`_: for other announcements intended for validator operators.
