Deploying Splitwise
===================

This section walks you through the steps required to host a separate
instance of the Splitwise back end. This can serve as an example 
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

    ../canton-research-2.6.0-SNAPSHOT/bin/coin --config examples/splitwise/splitwise-participant.conf \
      --bootstrap examples/splitwise/splitwise-participant.canton -DDOMAIN_URL=http://|cn_cluster|.network.canton.global:5008

Before starting the splitwise backend, some setup is required:

1. The service user and party used by the splitwise backend need to be allocated.
2. The splitwise DAR needs to be uploaded to the participant.

These steps are not performed by the splitwise backend itself since
they require admin claims while the backend runs without admin
claims. Instead, because the functionality is required by most apps,
it is built into the validator app that you already interacted with
when going through the :ref:`steps to self-host your validator
<self_hosted_validator>`.

To use the functionality in the validator app to upload DARs and
initialize users, you need to include the splitwise DAR and splitwise
service user in the ``app-instances`` section in your validator
configuration.

.. literalinclude:: ../../../../../../apps/app/src/pack/examples/splitwise/splitwise-validator.conf
    :start-after: BEGIN_SPLITWISE_CONFIG
    :end-before: END_SPLITWISE_CONFIG

The release bundle already contains a configuration file with that
section added so start your validator again with this configuration file:

.. parsed-literal::

  bin/coin --config examples/splitwise/splitwise-validator.conf -DNETWORK_APPS_ADDRESS=\ |cn_cluster|.network.canton.global

Now you can finally start the app backend. Splitwise is included as
part of the standard release so it is started using the same binary.

.. parsed-literal::

  bin/coin --config examples/splitwise/splitwise.conf -DNETWORK_APPS_ADDRESS=\ |cn_cluster|.network.canton.global

With the backend running, you can connect to it as a given user. Here,
we reuse the participant and the users ``alice`` and ``bob`` created
in the runbook for :ref:`hosting your own validator
<self_hosted_validator>`.

If you stopped the participant and validator, go through the runbook
again until you have onboarded ``alice`` and ``bob``.

Before Alice and Bob can use splitwise, you need to upload the DAR to
their participant. For that, go to the terminal in which you started
the Canton participant (configured using
``examples/validator/validator-participant.conf``) and run the
following command: ::

  @ validatorParticipant.dars.upload("dars/splitwise-0.1.0.dar")


To interact with splitwise, the release includes an example configuration that
provides two splitwise
references ``aliceSplitwise`` and ``bobSplitwise`` which interact with
splitwise using the ``alice`` and ``bob`` users that you onboarded
earlier.

.. parsed-literal::

  bin/coin --config examples/validator/splitwise-users.conf  -DNETWORK_APPS_ADDRESS=\ |cn_cluster|.network.canton.global

To verify that you setup everything correctly, create an install
request for ``alice`` followed by creating a group and listing your
groups: ::

  @ aliceSplitwise.createInstallRequest()

  @ aliceSplitwise.createGroup("mygroup")

  @ aliceSplitwise.listGroups()

  res4: Seq[...]] = Vector(
    Contract(
      contractId = ...,
      payload = ...,
    )
  )
