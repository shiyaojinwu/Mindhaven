package com.mindhaven.common.error;

public class HttpProblem extends RuntimeException {
    public final int status;

    public HttpProblem(int status, String message) {
        super(message);
        this.status = status;
    }
}
