package edu.assignment.hms;

public class ApiError extends RuntimeException {
    public final int status;
    public ApiError(int status, String message) { super(message); this.status = status; }
}
