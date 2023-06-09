.. _splitwell-provider:

Deploying Splitwell
===================

.. note::
   This section describes how you can run your own splitwell instance as an appliation provider. If you are only interested in
   using splitwell, you can connect to the splitwell instance :ref:`operated by DA <splitwell-user>`. To connect to the splitwell instance that you will setup
   in this section, follow those instructions and use ``http://localhost:5113`` as the URL for the splitwell backend.

This section walks you through the steps required to host a separate
instance of the Splitwell back end. This can serve as an example
for how to host other applications, including those you write yourself.

We assume that you already setup a self-hosted validator here. If you
have not done so before, please go through the :ref:`steps
<self_hosted_validator>` before coming back to this tutorial. You
should still have both the Canton participant and the CN apps running.

Following the :ref:`recommended topology <topology_image>` for
deploying Canton network applications, we run a separate validator
node for our application.

To start the validator node, first start the validator participant using the Canton research binary and connect it to the domain:

.. parsed-literal::

    DOMAIN_URL=http://|cn_cluster|.network.canton.global:5008 ../|canton_subdir|/bin/canton --config examples/splitwell/splitwell-participant.conf --bootstrap examples/splitwell/splitwell-participant.sc

Before starting the splitwell backend, some setup is required:

1. The service user and party used by the splitwell backend need to be allocated.
2. The splitwell DAR needs to be uploaded to the participant.

These steps are not performed by the splitwell backend itself since
they require admin claims while the backend runs without admin
claims. Instead, because the functionality is required by most apps,
it is built into the validator app that you already interacted with
when going through the :ref:`steps to self-host your validator
<self_hosted_validator>`.

To use the functionality in the validator app to upload DARs and
initialize users, you need to include the splitwell DAR and splitwell
service user in the ``app-instances`` section in your validator
configuration.

.. literalinclude:: ../../../../../../apps/app/src/pack/examples/splitwell/splitwell-validator.conf
    :start-after: BEGIN_SPLITWELL_CONFIG
    :end-before: END_SPLITWELL_CONFIG

The release bundle already contains a configuration file with that section added.

Request a validator onboarding secret...

.. parsed-literal::

   curl -X POST https://sv.sv-1.svc.\ |cn_cluster|.network.canton.global/api/v0/sv/devnet/onboard/validator/prepare | xargs -I _ sed 's#PLACEHOLDER#_#' examples/validator/validator-onboarding-nosecret.conf > validator-onboarding.conf

...and then start the splitwell validator with this configuration file and the onboarding config you just obtained:

.. parsed-literal::

  NETWORK_APPS_ADDRESS_PROTOCOL=https NETWORK_APPS_ADDRESS=\ |cn_cluster|.network.canton.global bin/cn-node --config examples/splitwell/splitwell-validator.conf --config validator-onboarding.conf

Now you can finally start the app backend. Splitwell is included as
part of the standard release so it is started using the same binary.

.. parsed-literal::

  NETWORK_APPS_ADDRESS_PROTOCOL=https NETWORK_APPS_ADDRESS=\ |cn_cluster|.network.canton.global bin/cn-node --config examples/splitwell/splitwell.conf

With the backend running, you can connect to it as a given user. Here,
we reuse the participant and the users ``alice`` and ``bob`` created
in the runbook for :ref:`hosting your own validator
<self_hosted_validator>`.

If you stopped the participant and validator, go through the runbook
again until you have onboarded ``alice`` and ``bob``.

Before Alice and Bob can use splitwell, you need to upload the DAR to
their participant. For that, go to the terminal in which you started
the Canton participant (configured using
``examples/validator/validator-participant.conf``) and run the
following command: ::

  @ validatorParticipant.dars.upload("dars/splitwell-0.1.0.dar")


To interact with splitwell, the release includes an example configuration that
provides two splitwell
references ``aliceSplitwell`` and ``bobSplitwell`` which interact with
splitwell using the ``alice`` and ``bob`` users that you onboarded
earlier.

.. parsed-literal::

  NETWORK_APPS_ADDRESS_PROTOCOL=https NETWORK_APPS_ADDRESS=\ |cn_cluster|.network.canton.global bin/cn-node --config examples/splitwell/splitwell-users.conf

To verify that you setup everything correctly, create an install
request for ``alice`` followed by creating a group and listing your
groups: ::

  @ aliceSplitwell.createInstallRequests()

  @ aliceSplitwell.requestGroup("mygroup")

  @ aliceSplitwell.listGroups()

Deploying Splitwell to a separate domain
========================================

In the example above, we deployed splitwell to the global domain
operated for the SVC. This can be a good option to get
started. However, over time you might want to operate your own domain
for your application. This provides you with better scalability,
reduces the domain fees you have to pay on the global domain,
increases privacy and lets you ensure availability of your app
independent of availability of the global domain.

To do so, first stop all existing Canton and Coin processes you had
running.

First, start the splitwell domain ::

  ../|canton_subdir|/bin/canton --config examples/splitwell/splitwell-domain.conf

Next, in a separate terminal, start the participant again using the same command that we used in the single-domain setup:

.. parsed-literal::

    DOMAIN_URL=http://|cn_cluster|.network.canton.global:5008 ../|canton_subdir|/bin/canton --config examples/splitwell/splitwell-participant.conf --bootstrap examples/splitwell/splitwell-participant.sc

Once the console opened, connect it to the domain you started earlier ::

  @ splitwellParticipant.domains.connect("splitwell", "http://localhost:5108")

With the participant being connected to both the global domain and the
splitwell domain, you can now start the validator again. First, request a new onboarding secret:

.. parsed-literal::

   curl -X POST https://sv.sv-1.svc.\ |cn_cluster|.network.canton.global/api/v0/sv/devnet/onboard/validator/prepare | xargs -I _ sed 's#PLACEHOLDER#_#' examples/validator/validator-onboarding-nosecret.conf > validator-onboarding.conf

Next start up the validator:

.. parsed-literal::

  NETWORK_APPS_ADDRESS_PROTOCOL=https NETWORK_APPS_ADDRESS=\ |cn_cluster|.network.canton.global bin/cn-node --config examples/splitwell/splitwell-validator.conf --config validator-onboarding.conf

And now start the app backend. Note that this time, we are configuring it to operate on the domain we started earlier:

.. parsed-literal::

   NETWORK_APPS_ADDRESS_PROTOCOL=https NETWORK_APPS_ADDRESS=\ |cn_cluster|.network.canton.global SPLITWELL_APP_DOMAIN=splitwell bin/cn-node --config examples/splitwell/splitwell.conf

With the backend running, you can connect to it as a given user. Here,
we reuse the participant and the users ``alice`` and ``bob`` created
in the runbook for :ref:`hosting your own validator
<self_hosted_validator>`.

If you stopped the participant and validator, go through the runbook
again until you have onboarded ``alice`` and ``bob``.

Upload the DAR to their participant: ::

  @ validatorParticipant.dars.upload("dars/splitwell-0.1.0.dar")

And connect their participant to the domain you setup above: ::

  @ validatorParticipant.domains.connect("splitwell", "http://localhost:5108")

And lastly, use the example configuration that you used in the
single-domain case as well which allows you to interact through
``aliceSplitwell`` and ``bobSplitwell``:

.. parsed-literal::

  NETWORK_APPS_ADDRESS_PROTOCOL=https NETWORK_APPS_ADDRESS=\ |cn_cluster|.network.canton.global bin/cn-node --config examples/splitwell/splitwell-users.conf

To validate that the setup works correctly, try creating a group: ::

  @ aliceSplitwell.createInstallRequests()

  @ aliceSplitwell.requestGroup("mygroup")

  @ aliceSplitwell.listGroups()
