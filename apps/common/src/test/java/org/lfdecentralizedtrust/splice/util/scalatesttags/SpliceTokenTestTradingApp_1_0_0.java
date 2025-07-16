package org.lfdecentralizedtrust.splice.util.scalatesttags;

import org.scalatest.TagAnnotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// Used to guard against running this test before the
// splice-token-test-trading-app was factored out of splice-token-standard-test
@TagAnnotation
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface SpliceTokenTestTradingApp_1_0_0 {}
