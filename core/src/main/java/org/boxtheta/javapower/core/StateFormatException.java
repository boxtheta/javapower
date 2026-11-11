package org.boxtheta.javapower.core;

import java.io.IOException;

/**
 * A saved machine state cannot be read: it is not a javapower state, it was written in a format
 * version this release does not read, or it is truncated or corrupt.
 *
 * @see StateInput
 */
public class StateFormatException extends IOException {
    private static final long serialVersionUID = 1L;

    public StateFormatException(String message) {
        super(message);
    }

    public StateFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
