.. _sv_onboarding:

Onboarding a SV
===============

These pages give a step-by-step guide how to deploy your own supervalidator (SV) node to the Canton network.

.. note::
  This section describes a local deployment where the relevant processes are launched locally on your machine.
  While that is useful for learning about the relevant pieces, and for testing, for actual operations of a node
  using Helm charts, please see :ref:`the instructions for spinning up an SV node in k8s <sv-helm>`


Prerequisites
-------------

The availability of an onboarded and fully operational validator node under your control is a requirement to operating an SV node.
Make sure you have completed the steps in :ref:`self_hosted_validator`,
including the steps to setup a Canton participant node.
However, if you still have the participant and validator running from the previous runbook, please terminate both (by hitting Ctrl+C in both terminals), we will be launching a new participant in this runbook.

SV nodes must be connected to a Canton participant node that is configured to use persistent storage via a PostgreSQL database:

.. literalinclude:: ../../../../../apps/app/src/pack/examples/sv/sv-participant.conf
    :start-after: participants {
    :end-before: supply

At this moment, this documentation does not cover setting up a Canton participant with persistent storage via PostgreSQL.
Please refer to the `Canton documentation on persistence`_.
Assuming that you have extended the ``examples/sv/sv-participant.conf`` to match your PostgreSQL deployment, start the participant using:

.. parsed-literal::

  DOMAIN_URL=http://|cn_cluster|.network.canton.global:5008 ../canton-research-2.7.0-SNAPSHOT/bin/canton --config examples/sv/sv-participant.conf --bootstrap examples/sv/sv-participant.sc

..
  For manually running this runbook using the same PostgreSQL setup we use for `./start-canton.sh`, run:
  ```
  scripts/start-postgres-for-preflight.sh
  ```
  Then run the canton command above, appending the following to the end ($REPO_ROOT should point to the root of our repo):
  ```
  --config $REPO_ROOT/apps/app/src/test/resources/include/storage-postgres.conf --config $REPO_ROOT/apps/app/src/test/resources/include/self-hosted-sv-participant-postgres-storage.conf
  ```

.. _Canton documentation on persistence: https://docs.daml.com/canton/usermanual/persistence.html

Like for hosting a validator, you also need to enable the GCP DA Canton DevNet VPN.
If you can view this documentation, you already enabled the VPN successfully.

.. _validator-secret:

Validator Onboarding
--------------------

An SV node includes a validator node, so just like when
:ref:`deploying a validator node <self_hosted_validator>`, you need to obtain a secret from your sponsoring SV:

.. parsed-literal::

   curl -X POST https://sv.sv-1.svc.|cn_cluster|.network.canton.global/api/v0/sv/devnet/onboard/validator/prepare | xargs -I _ sed 's#PLACEHOLDER#_#' examples/sv/validator-onboarding-nosecret.conf > validator-onboarding.conf

.. _sv-identity:

Generating an SV identity
-------------------------

SV operators are identified by a human-readable name and an EC public key.
This identification is stable across deployments of the Canton network.
You are, for example, expected to reuse your SV name and public key between (test-)network resets.

Use the following shell commands to generate a keypair in the format expected by the SV node software: ::

  # Generate the keypair
  openssl ecparam -name prime256v1 -genkey -noout -out sv-keys.pem

  # Encode the keys
  public_key_base64=$(openssl ec -in sv-keys.pem -pubout -outform DER 2>/dev/null | base64 -w 0)
  private_key_base64=$(openssl pkcs8 -topk8 -nocrypt -in sv-keys.pem -outform DER 2>/dev/null | base64 -w 0)

  # Output the keys
  echo "public-key = \"$public_key_base64\""
  echo "private-key = \"$private_key_base64\""

  # Clean up
  rm sv-keys.pem

..
  Based on `scripts/generate-sv-keys.sh`

These commands should result in an output similar to ::

  public-key = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE1eb+JkH2QFRCZedO/P5cq5d2+yfdwP+jE+9w3cT6BqfHxCd/PyA0mmWMePovShmf97HlUajFuN05kZgxvjcPQw=="
  private-key = "MEECAQAwEwYHKoZIzj0CAQYIKoZIzj0DAQcEJzAlAgEBBCBsFuFa7Eumkdg4dcf/vxIXgAje2ULVz+qTKP3s/tHqKw=="

Store both keys in a safe location.
You will be using them every time you want to deploy a new SV node, i.e., also when deploying an SV node to a different deployment of the Canton Network and for redeploying an SV node after a (test-)network reset.

The `public-key` and your desired *SV name* need to be approved by a threshold of currently active SVs in order for you to be able to join the network as an SV.
For DevNet and the current early version of TestNet, send the `public-key` and your desired SV name to your point of contact at Digital Asset (DA) and wait for confirmation that your SV identity has been approved and configured at existing SV nodes.

Configure your SV node
----------------------

An example SV node configuration with defaults that match the Canton participant configuration used above is provided in ``examples/sv/sv.conf``.
While this configuration is sufficient to operate an SV node that is already an established member of the SVC, an additional *onboarding* configuration is required for initializing your SV on its first start.
Edit the example ``examples/sv/sv-onboarding.conf`` to:

- (optional) Chose a different existing SV as an onboarding sponsor (via the ``sv-client`` key).
  This SV is going to play a key role in orchestrating the initialization of your SV into a fully operational SV.
  SV1 is operated by DA and is a safe default choice here, assuming that it has been configured to approve your SV (see previous step).

- Configure the exact same SV ``name`` and ``public_key`` that you submitted for approval in the previous step,
  as well as the matching ``private_key`` returned by the key generation commands above. [#]_

.. [#] Note that alternative forms of SV authentication and private key storage will be available in the future.
   As a workaround for increasing the security of your SV private key,
   the SV onboarding config can be removed from running nodes once onboarding has been completed.

Start the SV app
----------------

If your configuration is sound and your SV identity has been approved by a sufficient number of currently active SVs
(currently, this can be performed by DA once you generate and provide your identity information as explained above),
starting the SV app for the first time will automatically onboard your SV into a fully operational status.
To start the SV app, run the following command:

.. parsed-literal::

    NETWORK_APPS_ADDRESS_PROTOCOL=https NETWORK_APPS_ADDRESS=\ |cn_cluster|.network.canton.global bin/cn-node --config examples/sv/sv.conf --config examples/sv/sv-onboarding.conf --config validator-onboarding.conf --bootstrap examples/sv/sv.sc

Once the SV app has started and you can access the CN console, you can confirm that your SV node is fully operational by querying its debug endpoint: ::

  @ sv.getSvcInfo

.. _local-sv-wallet-ui:

Hosting the SV Wallet UI
------------------------

To view the coin balance of your SV, you can host the wallet UI and
then login as ``sv_wallet_user``.  To do so, first edit the wallet UI
config file ``web-uis/wallet/config.js`` and replace
``TARGET_CLUSTER`` with |cn_cluster_literal| as described in the :ref:`runbook
for self-hosted validators <configuring-wallet-ui>`.

Next run the following command: ::

  docker run -d --name nginx_cn_frontends --add-host=host.docker.internal:host-gateway -p 3000:3000 -v $(pwd)/examples/nginx/conf:/etc/nginx/conf.d -v $(pwd)/web-uis:/usr/share/nginx/html nginx


For more detailed instructions or in case of issues, refer to the docs for :ref:`hosting the wallet UI for a self-hosted validator<hosting-the-uis>`. The steps are identical for the SV wallet.

After you hosted it, open http://wallet.localhost:3000 in your browser
and login as ``sv_wallet_user``.

You will see your balance increase as mining rounds advance every 2.5
minutes and the transaction history will display
``sv_reward_collected`` entries.

Troubleshooting
---------------

If your SV node is unable to complete its first startup (after waiting for up to 2 minutes), consult the logs recorded to ``log/canton.log`` for hints about potential reasons.
You can also inspect these logs to confirm that your SV node is actively performing its service to the network, once onboarded. [#]_

.. [#] More sophisticated means of monitoring SV nodes will be made available soon.
