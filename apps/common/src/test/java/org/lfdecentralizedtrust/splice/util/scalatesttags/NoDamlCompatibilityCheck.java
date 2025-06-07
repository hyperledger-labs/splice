package org.lfdecentralizedtrust.splice.util.scalatesttags;

import java.lang.annotation.*;
import org.scalatest.TagAnnotation;

@TagAnnotation
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface NoDamlCompatibilityCheck {}
