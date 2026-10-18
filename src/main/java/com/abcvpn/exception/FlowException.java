package com.abcvpn.exception;

public class FlowException extends RuntimeException {
    private final ErrorCode errorCode;

    public FlowException(ErrorCode errorCode) {
        super(errorCode.message());
        this.errorCode = errorCode;
    }

    public FlowException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public FlowException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() { return errorCode; }
}
