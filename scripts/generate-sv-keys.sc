#!/usr/bin/env scala

import java.security.KeyPairGenerator
import java.util.Base64

val keyGen = KeyPairGenerator.getInstance("EC")
val keyPair = keyGen.generateKeyPair()

val publicKeyBase64 = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded())
val privateKeyBase64 = Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded())

println(s"public-key = \"$publicKeyBase64\"")
println(s"private-key = \"$privateKeyBase64\"")
