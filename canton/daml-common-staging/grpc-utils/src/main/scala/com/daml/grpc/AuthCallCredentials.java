// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.grpc;

import io.grpc.CallCredentials;
import io.grpc.Metadata;
import io.grpc.stub.AbstractStub;
import java.util.concurrent.Executor;

public final class AuthCallCredentials extends CallCredentials {

  private static Metadata.Key<String> header =
      Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER);

  public static <T extends AbstractStub<T>> T authorizingStub(T stub, String token) {
    return stub.withCallCredentials(new AuthCallCredentials(token));
  }

  private final String token;

  public AuthCallCredentials(String token) {
    super();
    this.token = token;
  }

  @Override
  public void applyRequestMetadata(
      RequestInfo requestInfo, Executor appExecutor, MetadataApplier applier) {
    Metadata metadata = new Metadata();
    metadata.put(
        AuthCallCredentials.header, token.startsWith("Bearer ") ? token : "Bearer " + token);
    applier.apply(metadata);
  }

  @Override
  public void thisUsesUnstableApi() {
    // No need to implement this, it's used as a warning from upstream
  }
}