package com.daml.network.migration

import com.digitalasset.canton.crypto.Hash
import com.google.protobuf.ByteString

final case class Dar(hash: Hash, content: ByteString)
