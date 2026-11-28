package org.apache.camel.kit.workflow;

import java.util.regex.Pattern;

/** Identifier accepted by commands that address a manual pipeline. */
public final class PipelineId {

    private static final Pattern FORMAT = Pattern.compile("[0-9]{3,}-[a-z0-9]+(?:-[a-z0-9]+)*");

    private PipelineId() {
    }

    public static boolean isValid(String value) {
        return value != null && FORMAT.matcher(value).matches();
    }
}
