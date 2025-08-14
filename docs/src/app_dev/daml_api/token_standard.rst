..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _token_standard_usage:

Wallet Integration with Token Standard Assets
=============================================

See the `ledger's OpenAPI definition <https://github.com/digital-asset/canton/blob/f608ec2cbb7b3e9331b7cc564eb260916606d815/community/ledger/ledger-json-api/src/test/resources/json-api-docs/openapi.yaml#L1#L1>`_.
This is also accessible at ``http(s)://${YOUR_PARTICIPANT}/docs/openapi``.
We encourage developers to use OpenAPI code generation tools as opposed to manually writing HTTP requests.

Check out the :ref:`Authentication section <app-auth>` for more information on how to authenticate the requests.


.. _token_standard_usage_reading_contracts:

Reading contracts implementing a Token Standard interface for a party
---------------------------------------------------------------------

Reference code from the Token Standard CLI  to `list contracts by interface <https://github.com/hyperledger-labs/splice/blob/main/token-standard/cli/src/commands/listContractsByInterface.ts>`_

The Token Standard includes several interfaces that are implemented by Daml templates.
To list all contracts implementing a particular interface,
you have to query the participant's `active-contracts endpoint <https://github.com/digital-asset/canton/blob/f608ec2cbb7b3e9331b7cc564eb260916606d815/community/ledger/ledger-json-api/src/test/resources/json-api-docs/openapi.yaml#L1#L620>`_.

The ``activeAtOffset`` parameter can be set to the result of
`the ledger-end endpoint on the participant <https://github.com/digital-asset/canton/blob/f608ec2cbb7b3e9331b7cc564eb260916606d815/community/ledger/ledger-json-api/src/test/resources/json-api-docs/openapi.yaml#L1#L711>`_
to get the latest ACS, or an older (non-pruned) one to get the ACS at that point in time.

To filter for a particular party and interface, it should include a ``filtersByParty`` with an ``InterfaceFilter``:

.. code-block:: json

  {
    "filtersByParty": {
      "$A_PARTY": {
        "cumulative": [
          {
            "identifierFilter": {
              "InterfaceFilter": {
                "value": {
                  "interfaceId": "$AN_INTERFACE_ID",
                  "includeInterfaceView": true,
                  "includeCreatedEventBlob": true
                }
              }
            }
          }
        ]
      }
    }
  }

For example:

* ``"$A_PARTY"`` could look like ``test::1220a0db3761b3fc919b55e7ff80ad740824336010bfde8829611c0e64477ab7bee5``.
* ``"$AN_INTERFACE_ID"`` could be ``#splice-api-token-holding-v1:Splice.Api.Token.HoldingV1:Holding``.

Additionally, there's three flags that can be set:

* ``includeInterfaceView``: to include the interface view of the contract in the response.
* ``includeCreatedEventBlob``: to include a binary blob that is required for `explicit disclosure <https://docs.daml.com/app-dev/explicit-contract-disclosure.html>`_.
* ``verbose``: to include additional information in the response.

The response for such a query will contain the ``createdEvent`` of the contract, including the interface views requested (if any).
The ``viewValue`` within it will be the JSON-serialized Daml interface view.
If more than one interface is requested, you can distinguish them by checking the ``interfaceId`` field.
You can find an `example response for Holdings here <https://github.com/hyperledger-labs/splice/blob/main/token-standard/cli/__tests__/mocks/data/holdings.json>`_.


.. _token_standard_usage_reading_tx_history:

Reading and parsing transaction history involving Token Standard contracts
--------------------------------------------------------------------------

`Token Standard CLI's code to list transactions <https://github.com/hyperledger-labs/splice/blob/main/token-standard/cli/src/commands/listHoldingTransactions.ts>`_

The participant has an `endpoint to list all transactions <https://github.com/digital-asset/canton/blob/f608ec2cbb7b3e9331b7cc564eb260916606d815/community/ledger/ledger-json-api/src/test/resources/json-api-docs/openapi.yaml#L1#L763>`_ involving the provided parties and interfaces.

To filter for a particular party and interface, it should include a ``filtersByParty`` with an ``InterfaceFilter``:

.. code-block:: json

    {
      "filtersByParty": {
        "$A_PARTY": {
          "cumulative": [
            {
              "identifierFilter": {
                "InterfaceFilter": {
                  "value": {
                    "interfaceId": "$AN_INTERFACE_ID",
                    "includeInterfaceView": true,
                    "includeCreatedEventBlob": true
                  }
                }
              }
            }
          ]
        }
      }
    }

For example:

* ``"$A_PARTY"`` could look like ``test::1220a0db3761b3fc919b55e7ff80ad740824336010bfde8829611c0e64477ab7bee5``.
* ``"$AN_INTERFACE_ID"`` could be ``#splice-api-token-holding-v1:Splice.Api.Token.HoldingV1:Holding``.

To include other transaction nodes that don't directly involve the interfaces (e.g., non-interface-specific children nodes),
a ``WildcardFilter`` can be included in the ``cumulative`` filter array:

.. code-block:: json

    {
      "identifierFilter": {
        "WildcardFilter": {
          "value": {
            "includeCreatedEventBlob": true
          }
        }
      }
    }

The ``beginExclusive`` field is the offset from which to start reading transactions.
To paginate, you can start with the ``participantPrunedUpToInclusive`` from ``GET ${PARTICIPANT_URL}/v2/state/latest-pruned-offsets``
and continue by passing the offset of the last transaction from the previous response.

Parsing the history
^^^^^^^^^^^^^^^^^^^

You can find an example parser `here <https://github.com/hyperledger-labs/splice/blob/main/token-standard/cli/src/txparse/parser.ts>`_.
This handles transactions involving the ``Holding`` and ``TransferInstruction`` interfaces.

The endpoint returns transaction trees as an array.
The transactions are ordered as they occur in the ledger.
Given an ``ExercisedEvent`` with ``nodeId=X`` and ``lastDescendantNodeId=Y``,
the children of that node are those with ``nodeId`` in the range ``[X+1, Y]``.
``CreatedEvent`` and ``ArchivedEvent`` (or equivalently, ``ExercisedEvent`` where ``consuming=true``) do not have children.

Given the above, a tree-like traversal can be performed on the transaction nodes.
Generally, a Token Standard parser will focus on the exercise of Token Standard choices and creation of contracts implementing Token Standard interfaces.
Where further customization is required, a parser can decide to also focus on internal/specific choices that are not available in the standard, but in some specific implementation.

In each Token Standard exercise node, one can find:

* The choice being executed, useful to distinguish what operation was performed.
* As part of the archival/creation of children, one can find out other relevant operations that happened. For example, creation or archival of ``Holdings``.
* Meta key/values, of which part of the standard:

  * ``splice.lfdecentralizedtrust.org/tx-kind``: the kind of operation happening in the node. This can give more information than the exercised choice does. It can be one of:

    * ``transfer``
    * ``merge-split``
    * ``burn``
    * ``mint``
    * ``unlock``
    * ``expire-dust``

  * ``splice.lfdecentralizedtrust.org/sender``: which party is the sender in the node.
  * ``splice.lfdecentralizedtrust.org/reason``: a text specifying the reason for the operation in the node.
  * ``splice.lfdecentralizedtrust.org/burned``: how much of a holding was burned in the node.

.. warning::

    Meta key/values can be specified in several fields. For transfers, they should be merged in last-write-wins order of:

    * event.choiceArgument?.transfer?.meta,
    * event.choiceArgument?.extraArgs?.meta,
    * event.choiceArgument?.meta,
    * event.exerciseResult?.meta,


.. _token_standard_usage_executing_factory_choice:

Executing a factory choice
--------------------------

`Token Standard CLI's code to create a transfer via TransferFactory <https://github.com/hyperledger-labs/splice/blob/main/token-standard/cli/src/commands/transfer.ts>`_

To execute a choice via a Token Standard factory, first you need need to fetch the factory from the corresponding registry.

.. note::

    The mapping from an instrument's `admin` party-id to the corresponding registry URL needs to be maintained currently by wallets themselves,
    until a generic solution (likely based on CNS) is implemented.

The registry will return the relevant factory in the corresponding endpoint:

* `TransferFactory </app_dev/token_standard_openapi/index.html#post--registry-transfer-instruction-v1-transfer-factory>`_
* `AllocationFactory </app_dev/token_standard_openapi/index.html#post--registry-allocation-instruction-v1-allocation-factory>`_

The response's payload will include three relevant fields:
* ``factoryId``: the contract id of the factory
* ``disclosedContracts``: must be provided to the exercise of the factory's choice for it to work

With this data, you can execute a choice on the factory. For external parties
you must call the `prepare <https://github.com/digital-asset/canton/blob/f608ec2cbb7b3e9331b7cc564eb260916606d815/community/ledger/ledger-json-api/src/test/resources/json-api-docs/openapi.yaml#L1#L1553>`_
and `execute <https://github.com/digital-asset/canton/blob/f608ec2cbb7b3e9331b7cc564eb260916606d815/community/ledger/ledger-json-api/src/test/resources/json-api-docs/openapi.yaml#L1#L1585>`_
endpoints of the participant.
For non-external parties, you can just use the `submit-and-wait endpoint <https://github.com/digital-asset/canton/blob/f608ec2cbb7b3e9331b7cc564eb260916606d815/community/ledger/ledger-json-api/src/test/resources/json-api-docs/openapi.yaml#L1#L6>`_.

In both cases, you must include an ``ExerciseCommand`` in your payload with the following fields:

* ``templateId``: the interface id of the factory you want to exercise the choice on. For example, ``#splice-api-token-transfer-instruction-v1:Splice.Api.Token.TransferInstructionV1:TransferFactory``.
* ``contractId``: the ``factoryId`` obtained from the registry.
* ``choice``: the name of the choice you want to execute. For example, ``TransferFactory_Transfer``.
* ``choiceArgument``: the arguments that will be passed to the Daml choice. These will be decoded from JSON.
  For a ``TransferFactory_Transfer``, this will include for example the sender, receiver and amount, among other fields.


.. _token_standard_usage_executing_nonfactory_choice:

Executing a non-factory choice
------------------------------

`Token Standard CLI's code to accept a transfer instruction <https://github.com/hyperledger-labs/splice/blob/main/token-standard/cli/src/commands/acceptTransferInstruction.ts>`_

To execute a choice on a contract implementing a Token Standard interface for external parties,
you must call the `prepare <https://github.com/digital-asset/canton/blob/f608ec2cbb7b3e9331b7cc564eb260916606d815/community/ledger/ledger-json-api/src/test/resources/json-api-docs/openapi.yaml#L1#L1553>`_
and `execute <https://github.com/digital-asset/canton/blob/f608ec2cbb7b3e9331b7cc564eb260916606d815/community/ledger/ledger-json-api/src/test/resources/json-api-docs/openapi.yaml#L1#L1585>`_
endpoints of the participant.
For non-external parties, you can just use the `submit-and-wait endpoint <https://github.com/digital-asset/canton/blob/f608ec2cbb7b3e9331b7cc564eb260916606d815/community/ledger/ledger-json-api/src/test/resources/json-api-docs/openapi.yaml#L1#L6>`_.

In both cases, you must include an ``ExerciseCommand`` in your payload with the following fields:

* ``templateId``: the interface id of the contract you want to exercise the choice on. For example, ``#splice-api-token-transfer-instruction-v1:Splice.Api.Token.TransferInstructionV1:TransferInstruction``.
* ``contractId``: the contract id of the contract you want to exercise the choice on. Typically, you'll get this from :ref:`the current ACS of a party <token_standard_usage_reading_contracts>`.
* ``choice``: the name of the choice you want to execute. For example, ``TransferInstruction_Accept``.
* ``choiceArgument``: the arguments that will be passed to the Daml choice. These will be decoded from JSON.

Where a ``context`` is required as part of the ``choiceArgument``, it can be fetched from the corresponding registry:

* `To accept a TransferInstruction </app_dev/token_standard_openapi/index.html#post--registry-transfer-instruction-v1-transferInstructionId-choice-contexts-accept>`_
* `To reject a TransferInstruction </app_dev/token_standard_openapi/index.html#post--registry-transfer-instruction-v1-transferInstructionId-choice-contexts-reject>`_
* `To withdraw a TransferInstruction </app_dev/token_standard_openapi/index.html#post--registry-transfer-instruction-v1-transferInstructionId-choice-contexts-withdraw>`_
* `To withdraw an Allocation </app_dev/token_standard_openapi/index.html#post--registry-allocations-v1-allocationId-choice-contexts-withdraw>`_
* `To cancel an Allocation </app_dev/token_standard_openapi/index.html#post--registry-allocations-v1-allocationId-choice-contexts-cancel>`_

The response of these endpoints include two fields:

* ``choiceContextData``: to be passed as ``context`` in the ``choiceArgument``.
* ``disclosedContracts``: to be passed in the submit or prepare request.

.. warning::

  Note that ``AllocationRequest_Reject`` and ``AllocationRequest_Withdraw`` should be called with an empty choice context.
  This is currently there as a potential future extension.



.. _token_standard_usage_workflows:

Token Standard Workflows
------------------------

Transfers
^^^^^^^^^

The workflow will follow the following steps:

* A party calls ``TransferFactory_Transfer`` to create a transfer.
* Depending on the implementation of the factory, one of three ``TransferInstructionResult_Output`` is possible:

  * ``Failed``: where the transfer did not succeed and all holdings (minus fees) have been returned to the sender.
  * ``Completed``: where the transfer succeeded and the receiver has received their holdings. No further action is required.
  * ``Pending``: where the transfer is pending further steps. This will include a ``transferInstructionCid``.
* If ``Pending``, The receiver party observes a ``TransferInstruction`` (which has the same contract id as above). Then:

  * The receiver can exercise ``TransferInstruction_Accept``, which again will return a ``TransferInstructionResult`` depending on success and whether further steps are required or not.
  * The receiver can exercise ``TransferInstruction_Reject``, same as above.
  * The sender can exercise ``TransferInstruction_Withdraw``, again returning a ``TransferInstructionResult``.
  * The registry can exercise ``TransferInstruction_Update``, again returning a ``TransferInstructionResult``.

Allocations
^^^^^^^^^^^

The workflow will follow the following steps:

* A registry creates as many ``AllocationRequests`` as required for a workflow to happen.
* Parties can:

  * The registry can exercise ``AllocationInstruction_Withdraw`` or ``AllocationInstruction_Update`` on it.
  * The senders of each transfer leg can exercise ``AllocationFactory_Allocate`` to create an ``Allocation`` satisfying the conditions of the ``AllocationRequest``.
  * The following choices can be called on the ``Allocation``:

    * Sender, receiver and registry can jointly exercise ``Allocation_ExecuteTransfer``: to execute the allocated transfer.
    * Sender, receiver and registry can exercise ``Allocation_Cancel``, which consumes it.
    * The sender can exercise ``Allocation_Withdraw``, which consumes it.

