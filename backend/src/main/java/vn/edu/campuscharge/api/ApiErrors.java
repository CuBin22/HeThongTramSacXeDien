package vn.edu.campuscharge.api;

import java.sql.SQLException;
import java.util.Set;
import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ApiErrors {
    private static final Set<String> CONFLICT_STATES = Set.of(
        "22023", "P0001", "23502", "23503", "23505", "23514", "23P01");
    private static final Set<String> UNAVAILABLE_STATES = Set.of("57P01", "57P02", "57P03");

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ProblemDetail> databaseFailure(DataAccessException error) {
        HttpStatus status = error instanceof DataAccessResourceFailureException
            || error instanceof TransientDataAccessResourceException
            || error instanceof QueryTimeoutException
            || error instanceof RecoverableDataAccessException
            ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.INTERNAL_SERVER_ERROR;
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getSQLState() != null) {
                String state = sql.getSQLState();
                if (CONFLICT_STATES.contains(state)) {
                    status = HttpStatus.CONFLICT;
                    break;
                }
                if (state.startsWith("08") || UNAVAILABLE_STATES.contains(state)) {
                    status = HttpStatus.SERVICE_UNAVAILABLE;
                    break;
                }
            }
        }
        String detail = switch (status) {
            case CONFLICT -> "Operation conflicts with the current reservation or device state";
            case SERVICE_UNAVAILABLE -> "Database is temporarily unavailable";
            default -> "An unexpected database error occurred";
        };
        return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, detail));
    }
}
