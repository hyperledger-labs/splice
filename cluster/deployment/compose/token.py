#!/usr/bin/env python

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

import sys
import jwt
import time

if (len(sys.argv) != 2):
    print("Usage: token.py <username>")
    sys.exit(1)

username = sys.argv[1]

iat=int(time.time())
code = jwt.encode({'iat':iat,'aud':'https://canton.network.global','sub':username}, 'unsafe', algorithm='HS256')

print(code)
