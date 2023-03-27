package com.daml.network.store

trait AcsWithTxLogStore[TXI <: TxLogStore.IndexRecord, TXE <: TxLogStore.Entry[TXI]]
    extends AcsStore
    with TxLogStore[TXI, TXE] {}
