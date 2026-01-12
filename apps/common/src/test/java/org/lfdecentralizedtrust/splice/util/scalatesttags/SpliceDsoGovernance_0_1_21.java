package org.lfdecentralizedtrust.splice.util.scalatesttags;

import org.scalatest.TagAnnotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// Don't run this test when testing against splice-dso-governance < 0.1.21
@TagAnnotation
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface SpliceDsoGovernance_0_1_21 {}
