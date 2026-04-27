package cn.tesseract.crosshook;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Hook {
    String CONSTRUCTOR = "<init>";
    String HEAD = "head";
    String TAIL = "tail";

    String targetClass() default "";

    String targetMethod() default "";

    String injector() default "";

    int priority() default 0;
}