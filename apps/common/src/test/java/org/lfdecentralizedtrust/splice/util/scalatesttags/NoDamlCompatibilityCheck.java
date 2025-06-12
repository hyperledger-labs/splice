package org.lfdecentralizedtrust.splice.util.scalatesttags;

import java.lang.annotation.*;
import org.scalatest.TagAnnotation;

// Don't run this test when testing against older Daml versions.
@TagAnnotation
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface NoDamlCompatibilityCheck {}
