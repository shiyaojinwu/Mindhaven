package com.mindhaven.service.agent;

/** Recoverable model argument error; permission and execution errors are not swallowed. */
public class ToolArgumentException extends IllegalArgumentException {
    public ToolArgumentException(String message) { super(message); }
}
