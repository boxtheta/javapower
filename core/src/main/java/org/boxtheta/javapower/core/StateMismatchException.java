package org.boxtheta.javapower.core;

import java.io.IOException;

/**
 * A saved machine state is valid but was saved from a machine with different hardware (CPU
 * count, RAM size, devices) than the one it is being loaded into. The message lists every
 * difference. Nothing has been changed when it is thrown.
 */
public class StateMismatchException extends IOException {
    private static final long serialVersionUID = 1L;

    public StateMismatchException(String message) {
        super(message);
    }
}
