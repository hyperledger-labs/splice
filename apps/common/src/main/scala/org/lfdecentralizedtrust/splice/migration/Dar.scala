// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.migration

import com.digitalasset.canton.crypto.Hash
import com.google.protobuf.ByteString

final case class Dar(hash: Hash, content: ByteString)
