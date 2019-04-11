package org.jbpm.process.core.datatype.impl.coverter;

import java.util.function.Function;


public class NoOpTypeConverter implements Function<String, String> {

    @Override
    public String apply(String t) {
        return t;
    }

    
}
