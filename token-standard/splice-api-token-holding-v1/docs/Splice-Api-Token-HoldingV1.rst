.. _module-splice-api-token-holdingv1-43900:

Splice.Api.Token.HoldingV1
==========================

Types and interfaces for retrieving an investor's holdings\.

Interfaces
----------

.. _type-splice-api-token-holdingv1-holding-25898:

**interface** `Holding <type-splice-api-token-holdingv1-holding-25898_>`_

  Holding interface\.

  **viewtype** `HoldingView <type-splice-api-token-holdingv1-holdingview-83501_>`_

  + **Choice** Archive

    Controller\: Signatories of implementing template

    Returns\: ()

    (no fields)


Data Types
----------

.. _type-splice-api-token-holdingv1-holdingview-83501:

**data** `HoldingView <type-splice-api-token-holdingv1-holdingview-83501_>`_

  View for ``Holding``\.

  .. _constr-splice-api-token-holdingv1-holdingview-75848:

  `HoldingView <constr-splice-api-token-holdingv1-holdingview-75848_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - owner
         - `Party <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_
         - Owner of the holding\.
       * - instrumentId
         - `InstrumentId <type-splice-api-token-holdingv1-instrumentid-28218_>`_
         - Instrument being held\.
       * - amount
         - `Decimal <https://docs.daml.com/daml/stdlib/Prelude.html#type-ghc-types-decimal-18135>`_
         - Size of the holding\.
       * - lock
         - `Optional <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ `Lock <type-splice-api-token-holdingv1-lock-70295_>`_
         - Lock on the holding\.  Registries SHOULD allow holdings with expired locks as inputs to transfers to enable a combined unlocking \+ use choice\.
       * - meta
         - Metadata
         - Metadata\.

  **instance** `Eq <https://docs.daml.com/daml/stdlib/Prelude.html#class-ghc-classes-eq-22713>`_ `HoldingView <type-splice-api-token-holdingv1-holdingview-83501_>`_

  **instance** `Show <https://docs.daml.com/daml/stdlib/Prelude.html#class-ghc-show-show-65360>`_ `HoldingView <type-splice-api-token-holdingv1-holdingview-83501_>`_

  **instance** `HasFromAnyView <https://docs.daml.com/daml/stdlib/DA-Internal-Interface-AnyView.html#class-da-internal-interface-anyview-hasfromanyview-30108>`_ `Holding <type-splice-api-token-holdingv1-holding-25898_>`_ `HoldingView <type-splice-api-token-holdingv1-holdingview-83501_>`_

  **instance** `HasInterfaceView <https://docs.daml.com/daml/stdlib/Prelude.html#class-da-internal-interface-hasinterfaceview-4492>`_ `Holding <type-splice-api-token-holdingv1-holding-25898_>`_ `HoldingView <type-splice-api-token-holdingv1-holdingview-83501_>`_

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"amount\" `HoldingView <type-splice-api-token-holdingv1-holdingview-83501_>`_ `Decimal <https://docs.daml.com/daml/stdlib/Prelude.html#type-ghc-types-decimal-18135>`_

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"instrumentId\" `HoldingView <type-splice-api-token-holdingv1-holdingview-83501_>`_ `InstrumentId <type-splice-api-token-holdingv1-instrumentid-28218_>`_

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"lock\" `HoldingView <type-splice-api-token-holdingv1-holdingview-83501_>`_ (`Optional <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ `Lock <type-splice-api-token-holdingv1-lock-70295_>`_)

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"meta\" `HoldingView <type-splice-api-token-holdingv1-holdingview-83501_>`_ Metadata

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"owner\" `HoldingView <type-splice-api-token-holdingv1-holdingview-83501_>`_ `Party <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"amount\" `HoldingView <type-splice-api-token-holdingv1-holdingview-83501_>`_ `Decimal <https://docs.daml.com/daml/stdlib/Prelude.html#type-ghc-types-decimal-18135>`_

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"instrumentId\" `HoldingView <type-splice-api-token-holdingv1-holdingview-83501_>`_ `InstrumentId <type-splice-api-token-holdingv1-instrumentid-28218_>`_

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"lock\" `HoldingView <type-splice-api-token-holdingv1-holdingview-83501_>`_ (`Optional <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ `Lock <type-splice-api-token-holdingv1-lock-70295_>`_)

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"meta\" `HoldingView <type-splice-api-token-holdingv1-holdingview-83501_>`_ Metadata

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"owner\" `HoldingView <type-splice-api-token-holdingv1-holdingview-83501_>`_ `Party <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_

.. _type-splice-api-token-holdingv1-instrumentid-28218:

**data** `InstrumentId <type-splice-api-token-holdingv1-instrumentid-28218_>`_

  A globally unique identifier for instruments\.

  .. _constr-splice-api-token-holdingv1-instrumentid-17961:

  `InstrumentId <constr-splice-api-token-holdingv1-instrumentid-17961_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - admin
         - `Party <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_
         - The party representing the registry app that administers the instrument\.
       * - id
         - `Text <https://docs.daml.com/daml/stdlib/Prelude.html#type-ghc-types-text-51952>`_
         - The identifier used for the instrument by the instrument admin\.  This identifier MUST be unique and unambiguous per instrument admin\.

  **instance** `Eq <https://docs.daml.com/daml/stdlib/Prelude.html#class-ghc-classes-eq-22713>`_ `InstrumentId <type-splice-api-token-holdingv1-instrumentid-28218_>`_

  **instance** `Ord <https://docs.daml.com/daml/stdlib/Prelude.html#class-ghc-classes-ord-6395>`_ `InstrumentId <type-splice-api-token-holdingv1-instrumentid-28218_>`_

  **instance** `Show <https://docs.daml.com/daml/stdlib/Prelude.html#class-ghc-show-show-65360>`_ `InstrumentId <type-splice-api-token-holdingv1-instrumentid-28218_>`_

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"admin\" `InstrumentId <type-splice-api-token-holdingv1-instrumentid-28218_>`_ `Party <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"id\" `InstrumentId <type-splice-api-token-holdingv1-instrumentid-28218_>`_ `Text <https://docs.daml.com/daml/stdlib/Prelude.html#type-ghc-types-text-51952>`_

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"instrumentId\" `HoldingView <type-splice-api-token-holdingv1-holdingview-83501_>`_ `InstrumentId <type-splice-api-token-holdingv1-instrumentid-28218_>`_

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"admin\" `InstrumentId <type-splice-api-token-holdingv1-instrumentid-28218_>`_ `Party <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"id\" `InstrumentId <type-splice-api-token-holdingv1-instrumentid-28218_>`_ `Text <https://docs.daml.com/daml/stdlib/Prelude.html#type-ghc-types-text-51952>`_

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"instrumentId\" `HoldingView <type-splice-api-token-holdingv1-holdingview-83501_>`_ `InstrumentId <type-splice-api-token-holdingv1-instrumentid-28218_>`_

.. _type-splice-api-token-holdingv1-lock-70295:

**data** `Lock <type-splice-api-token-holdingv1-lock-70295_>`_

  Details of a lock\.

  .. _constr-splice-api-token-holdingv1-lock-56452:

  `Lock <constr-splice-api-token-holdingv1-lock-56452_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - holders
         - \[`Party <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_\]
         - Unique list of parties which are locking the contract\. (Represented as a list, as that has the better JSON encoding\.)
       * - expiresAt
         - `Optional <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ `Time <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-time-63886>`_
         - Absolute, inclusive deadline as of which the lock expires\.
       * - expiresAfter
         - `Optional <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ `RelTime <https://docs.daml.com/daml/stdlib/DA-Time.html#type-da-time-types-reltime-23082>`_
         - Duration after which the created lock expires\. Measured relative to the ledger time that the locked holding contract was created\.  If both ``expiresAt`` and ``expiresAfter`` are set, the lock expires at the earlier of the two times\.
       * - context
         - `Optional <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ `Text <https://docs.daml.com/daml/stdlib/Prelude.html#type-ghc-types-text-51952>`_
         - Short, human\-readable description of the context of the lock\. Used by wallets to enable users to understand the reason for the lock\.  Note that the visibility of the content in this field might be wider than the visibility of the contracts in the context\. You should thus carefully decide what information is safe to put in the lock context\.

  **instance** `Eq <https://docs.daml.com/daml/stdlib/Prelude.html#class-ghc-classes-eq-22713>`_ `Lock <type-splice-api-token-holdingv1-lock-70295_>`_

  **instance** `Ord <https://docs.daml.com/daml/stdlib/Prelude.html#class-ghc-classes-ord-6395>`_ `Lock <type-splice-api-token-holdingv1-lock-70295_>`_

  **instance** `Show <https://docs.daml.com/daml/stdlib/Prelude.html#class-ghc-show-show-65360>`_ `Lock <type-splice-api-token-holdingv1-lock-70295_>`_

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"context\" `Lock <type-splice-api-token-holdingv1-lock-70295_>`_ (`Optional <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ `Text <https://docs.daml.com/daml/stdlib/Prelude.html#type-ghc-types-text-51952>`_)

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"expiresAfter\" `Lock <type-splice-api-token-holdingv1-lock-70295_>`_ (`Optional <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ `RelTime <https://docs.daml.com/daml/stdlib/DA-Time.html#type-da-time-types-reltime-23082>`_)

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"expiresAt\" `Lock <type-splice-api-token-holdingv1-lock-70295_>`_ (`Optional <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ `Time <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-time-63886>`_)

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"holders\" `Lock <type-splice-api-token-holdingv1-lock-70295_>`_ \[`Party <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_\]

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"lock\" `HoldingView <type-splice-api-token-holdingv1-holdingview-83501_>`_ (`Optional <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ `Lock <type-splice-api-token-holdingv1-lock-70295_>`_)

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"context\" `Lock <type-splice-api-token-holdingv1-lock-70295_>`_ (`Optional <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ `Text <https://docs.daml.com/daml/stdlib/Prelude.html#type-ghc-types-text-51952>`_)

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"expiresAfter\" `Lock <type-splice-api-token-holdingv1-lock-70295_>`_ (`Optional <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ `RelTime <https://docs.daml.com/daml/stdlib/DA-Time.html#type-da-time-types-reltime-23082>`_)

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"expiresAt\" `Lock <type-splice-api-token-holdingv1-lock-70295_>`_ (`Optional <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ `Time <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-time-63886>`_)

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"holders\" `Lock <type-splice-api-token-holdingv1-lock-70295_>`_ \[`Party <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_\]

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"lock\" `HoldingView <type-splice-api-token-holdingv1-holdingview-83501_>`_ (`Optional <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ `Lock <type-splice-api-token-holdingv1-lock-70295_>`_)
