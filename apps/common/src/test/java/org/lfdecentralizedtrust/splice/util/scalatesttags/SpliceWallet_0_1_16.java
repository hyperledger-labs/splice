package org.lfdecentralizedtrust.splice.util.scalatesttags;

import java.lang.annotation.*;
import org.scalatest.TagAnnotation;

// Don't run this test when testing against splice-wallet < 0.1.16
@TagAnnotation
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface SpliceWallet_0_1_16 {}
