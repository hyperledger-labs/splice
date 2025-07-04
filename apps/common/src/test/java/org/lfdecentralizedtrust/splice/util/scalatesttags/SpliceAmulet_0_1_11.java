package org.lfdecentralizedtrust.splice.util.scalatesttags;

import org.scalatest.TagAnnotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// Don't run this test when testing against splice-amulet < 0.1.11
// Typically used to guard tests that rely on being able to set the expectedDsoParty on calls to AmuletRules
// and ExternalAmuletRules
@TagAnnotation
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface SpliceAmulet_0_1_11 {}
