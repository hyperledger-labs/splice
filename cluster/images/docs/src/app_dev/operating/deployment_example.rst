Deploying Splitwise
===================

This section walks you through the steps required to host a separate
instance of the Splitwise back end. This can serve as an example 
for how to host other applications, including those you write yourself.

We assume that you already setup a self-hosted validator here. If you
have not done so before, please go through the :ref:`steps
<self_hosted_validator>` before coming back to this tutorial. You
should still have both the Canton participant and the CN apps running.

Before starting the splitwise backend, some setup is required:

1. The service user and party used by the splitwise backend need to be allocated.
2. The splitwise DAR needs to be uploaded to the participant.

These steps are not performed by the splitwise backend itself since
they require admin claims while the backend runs without admin
claims. Instead, because the functionality is required by most apps,
it is built into the validator app that you already interacted with
when going through the :ref:`steps to self-host your validator
<self_hosted_validator>`.

To keep things simple here, we host the application on the same
validator that we used earlier. However, note that this is not a
requirement. Your users can be hosted on different validator nodes
than the application.

To use the functionality in the validator app to upload DARs and
initialize users, first stop your current validator by stopping the
process ``bin/coin --config examples/validator/validator.conf`` that
you started before. You now need to change the ``app-instances``
section in ``validator.conf`` to include the splitwise DAR and the
splitwise service user.

.. literalinclude:: ../../../../../../apps/app/src/pack/examples/validator/validator-splitwise.conf
    :start-after: BEGIN_SPLITWISE_CONFIG
    :end-before: END_SPLITWISE_CONFIG

The release bundle already contains a configuration file with that
section added so start your validator again with this configuration file: ::

  bin/coin --config examples/validator/validator-splitwise.conf

Now you can finally start the app backend. Spltiwise is included as
part of the standard release so it is started using the same binary. ::

  bin/coin --config examples/validator/splitwise.conf

With the backend running, you can connect to it as a given user. The
release includes an example configuration that provides two splitwise
references ``aliceSplitwise`` and ``bobSplitwise`` which interact with
splitwise using the ``alice`` and ``bob`` users that you onboarded
earlier. ::

  bin/coin --config examples/validator/splitwise-users.conf

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
