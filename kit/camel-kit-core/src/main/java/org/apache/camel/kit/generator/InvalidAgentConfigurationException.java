package org.apache.camel.kit.generator;

import java.io.IOException;
import java.nio.file.AccessDeniedException;

/**
 * Signals invalid existing agent configuration that must be reported without starting generation.
 */
public final class InvalidAgentConfigurationException extends IOException {

    public InvalidAgentConfigurationException(String message) {
        super(message);
    }

    public InvalidAgentConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }

    public static InvalidAgentConfigurationException unreadableDirectory(AccessDeniedException cause) {
        return new InvalidAgentConfigurationException(
                "Cannot inspect " + cause.getFile() + ". Restore read and search permissions and retry.", cause);
    }
}
