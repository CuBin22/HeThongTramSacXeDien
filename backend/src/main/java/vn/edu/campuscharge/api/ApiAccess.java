package vn.edu.campuscharge.api;

import java.security.Principal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

public final class ApiAccess {
    private ApiAccess() {}
    public static UUID user(Principal principal) {
        if (principal == null || principal instanceof Authentication auth && !auth.isAuthenticated())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        try { return UUID.fromString(principal.getName()); }
        catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid identity"); }
    }
    public static String key(String key) {
        if (key == null || key.isBlank() || key.length() > 200)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key must contain 1 to 200 characters");
        return key;
    }
    public static void owner(UUID expected, UUID actual) {
        if (!expected.equals(actual)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Resource belongs to another user");
    }
    public static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
    public static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "Resource not found"); }
}
