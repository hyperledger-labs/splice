package org.lfdecentralizedtrust.splice.util.scalatesttags;

import java.lang.annotation.*;
import org.scalatest.TagAnnotation;

// Don't run this test when testing against splice-amulet < 0.1.9
@TagAnnotation
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface SpliceAmulet_0_1_9 {}
