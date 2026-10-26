package edu.assignment.hms;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import java.sql.SQLException;
import java.util.Map;

@RestControllerAdvice
public class ErrorHandler {
    private static final Logger log = LoggerFactory.getLogger(ErrorHandler.class);
    @ExceptionHandler(ApiError.class)
    ResponseEntity<?> api(ApiError e) { return ResponseEntity.status(e.status).body(Map.of("message", e.getMessage())); }
    @ExceptionHandler({IllegalArgumentException.class, org.springframework.http.converter.HttpMessageNotReadableException.class})
    ResponseEntity<?> invalid(Exception e) { return ResponseEntity.badRequest().body(Map.of("message","Invalid input. Check the entered values.")); }
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<?> large(Exception e) { return ResponseEntity.status(413).body(Map.of("message","The maximum file size is 5 MB.")); }
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> other(Exception e) {
        Throwable t = e;
        while (t.getCause() != null) t = t.getCause();
        if (t instanceof SQLException s && s.getSQLState() != null && s.getSQLState().startsWith("23"))
            return ResponseEntity.status(409).body(Map.of("message","A duplicate record or invalid linked record was detected."));
        log.error("Unhandled API failure", e);
        return ResponseEntity.internalServerError().body(Map.of("message","The request could not be completed. Check the server log."));
    }
}
