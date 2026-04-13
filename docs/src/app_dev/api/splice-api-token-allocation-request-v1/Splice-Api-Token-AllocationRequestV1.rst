.. _module-splice-api-token-allocationrequestv1-2106:

Splice.Api.Token.AllocationRequestV1
====================================

This module defines the interface for an ``AllocationRequest``, which is an interface that can
be implemented by an app to request specific allocations from their users
for the purpose of settling a DvP or a payment as part of an app's workflow\.

Interfaces
----------

.. _type-splice-api-token-allocationrequestv1-allocationrequest-90278:

**interface** `AllocationRequest <type-splice-api-token-allocationrequestv1-allocationrequest-90278_>`_

  A request by an app for allocations to be created to enable the execution of a settlement\.

  Apps are free to use a single request spanning all senders or one request per sender\.

  **viewtype** `AllocationRequestView <type-splice-api-token-allocationrequestv1-allocationrequestview-51417_>`_

  + .. _type-splice-api-token-allocationrequestv1-allocationrequestreject-89381:

    **Choice** `AllocationRequest_Reject <type-splice-api-token-allocationrequestv1-allocationrequestreject-89381_>`_

    Reject an allocation request\.

    Implementations SHOULD allow any sender of a transfer leg to reject the allocation request,
    and thereby signal that they are definitely not going to create a matching allocation for the settlement\.

    Controller\: actor

    Returns\: ChoiceExecutionMetadata

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - actor
         - `Party <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_
         - The party rejecting the allocation request\.
       * - extraArgs
         - ExtraArgs
         - Additional context required in order to exercise the choice\.

  + .. _type-splice-api-token-allocationrequestv1-allocationrequestwithdraw-15628:

    **Choice** `AllocationRequest_Withdraw <type-splice-api-token-allocationrequestv1-allocationrequestwithdraw-15628_>`_

    Withdraw an allocation request as the executor\.

    Used by executors to withdraw the allocation request if they are unable to execute it;
    e\.g\., because a trade has been cancelled\.

    Controller\: (DA\.Internal\.Record\.getField @\"executor\" (DA\.Internal\.Record\.getField @\"settlement\" (view this)))

    Returns\: ChoiceExecutionMetadata

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - extraArgs
         - ExtraArgs
         - Additional context required in order to exercise the choice\.

  + **Choice** Archive

    Controller\: Signatories of implementing template

    Returns\: ()

    (no fields)

  + **Method allocationRequest\_RejectImpl \:** `ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `AllocationRequest <type-splice-api-token-allocationrequestv1-allocationrequest-90278_>`_ \-\> `AllocationRequest_Reject <type-splice-api-token-allocationrequestv1-allocationrequestreject-89381_>`_ \-\> `Update <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ ChoiceExecutionMetadata

  + **Method allocationRequest\_WithdrawImpl \:** `ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `AllocationRequest <type-splice-api-token-allocationrequestv1-allocationrequest-90278_>`_ \-\> `AllocationRequest_Withdraw <type-splice-api-token-allocationrequestv1-allocationrequestwithdraw-15628_>`_ \-\> `Update <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ ChoiceExecutionMetadata

Data Types
----------

.. _type-splice-api-token-allocationrequestv1-allocationrequestview-51417:

**data** `AllocationRequestView <type-splice-api-token-allocationrequestv1-allocationrequestview-51417_>`_

  View of ``AllocationRequest``\.

  Implementations SHOULD make sure that at least all senders of the transfer legs
  are observers of the implementing contract, so that their wallet can show
  the request to them\.

  .. _constr-splice-api-token-allocationrequestv1-allocationrequestview-59188:

  `AllocationRequestView <constr-splice-api-token-allocationrequestv1-allocationrequestview-59188_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - settlement
         - SettlementInfo
         - Settlement for which the assets are requested to be allocated\.
       * - transferLegs
         - `TextMap <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-textmap-11691>`_ TransferLeg
         - Transfer legs that are requested to be allocated for the execution of the settlement keyed by their identifier\.  This may or may not be a complete list of transfer legs that are part of the settlement, depending on the confidentiality requirements of the app\.
       * - meta
         - Metadata
         - Additional metadata specific to the allocation request, used for extensibility\.

  **instance** `Eq <https://docs.daml.com/daml/stdlib/Prelude.html#class-ghc-classes-eq-22713>`_ `AllocationRequestView <type-splice-api-token-allocationrequestv1-allocationrequestview-51417_>`_

  **instance** `Show <https://docs.daml.com/daml/stdlib/Prelude.html#class-ghc-show-show-65360>`_ `AllocationRequestView <type-splice-api-token-allocationrequestv1-allocationrequestview-51417_>`_

  **instance** `HasFromAnyView <https://docs.daml.com/daml/stdlib/DA-Internal-Interface-AnyView.html#class-da-internal-interface-anyview-hasfromanyview-30108>`_ `AllocationRequest <type-splice-api-token-allocationrequestv1-allocationrequest-90278_>`_ `AllocationRequestView <type-splice-api-token-allocationrequestv1-allocationrequestview-51417_>`_

  **instance** `HasInterfaceView <https://docs.daml.com/daml/stdlib/Prelude.html#class-da-internal-interface-hasinterfaceview-4492>`_ `AllocationRequest <type-splice-api-token-allocationrequestv1-allocationrequest-90278_>`_ `AllocationRequestView <type-splice-api-token-allocationrequestv1-allocationrequestview-51417_>`_

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"meta\" `AllocationRequestView <type-splice-api-token-allocationrequestv1-allocationrequestview-51417_>`_ Metadata

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"settlement\" `AllocationRequestView <type-splice-api-token-allocationrequestv1-allocationrequestview-51417_>`_ SettlementInfo

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"transferLegs\" `AllocationRequestView <type-splice-api-token-allocationrequestv1-allocationrequestview-51417_>`_ (`TextMap <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-textmap-11691>`_ TransferLeg)

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"meta\" `AllocationRequestView <type-splice-api-token-allocationrequestv1-allocationrequestview-51417_>`_ Metadata

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"settlement\" `AllocationRequestView <type-splice-api-token-allocationrequestv1-allocationrequestview-51417_>`_ SettlementInfo

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"transferLegs\" `AllocationRequestView <type-splice-api-token-allocationrequestv1-allocationrequestview-51417_>`_ (`TextMap <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-textmap-11691>`_ TransferLeg)

Functions
---------

.. _function-splice-api-token-allocationrequestv1-allocationrequestrejectimpl-48931:

`allocationRequest_RejectImpl <function-splice-api-token-allocationrequestv1-allocationrequestrejectimpl-48931_>`_
  \: `AllocationRequest <type-splice-api-token-allocationrequestv1-allocationrequest-90278_>`_ \-\> `ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `AllocationRequest <type-splice-api-token-allocationrequestv1-allocationrequest-90278_>`_ \-\> `AllocationRequest_Reject <type-splice-api-token-allocationrequestv1-allocationrequestreject-89381_>`_ \-\> `Update <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ ChoiceExecutionMetadata

.. _function-splice-api-token-allocationrequestv1-allocationrequestwithdrawimpl-31866:

`allocationRequest_WithdrawImpl <function-splice-api-token-allocationrequestv1-allocationrequestwithdrawimpl-31866_>`_
  \: `AllocationRequest <type-splice-api-token-allocationrequestv1-allocationrequest-90278_>`_ \-\> `ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `AllocationRequest <type-splice-api-token-allocationrequestv1-allocationrequest-90278_>`_ \-\> `AllocationRequest_Withdraw <type-splice-api-token-allocationrequestv1-allocationrequestwithdraw-15628_>`_ \-\> `Update <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ ChoiceExecutionMetadata
