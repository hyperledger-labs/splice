package org.lfdecentralizedtrust.splice.util.scalatesttags;

import java.lang.annotation.*;
import org.scalatest.TagAnnotation;

// Don't run this test when testing against splice-amulet-name-service < 0.1.15
@TagAnnotation
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface SpliceAmuletNameService_0_1_15 {}
