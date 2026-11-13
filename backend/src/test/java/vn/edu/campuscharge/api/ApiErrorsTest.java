package vn.edu.campuscharge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

class ApiErrorsTest {
    private final ApiErrors handler = new ApiErrors();

    @ParameterizedTest
    @ValueSource(strings = {"22023", "P0001", "23502", "23503", "23505", "23514", "23P01"})
    void knownPostgresRejectionsRemainConflicts(String sqlState) {
        var response = handler.databaseFailure(new DataIntegrityViolationException("private database detail",
            new SQLException("private SQL detail", sqlState)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().getDetail()).doesNotContain("private");
    }

    @ParameterizedTest
    @MethodSource("unavailableDatabaseErrors")
    void unavailableDatabaseReturnsServiceUnavailable(DataAccessException error) {
        var response = handler.databaseFailure(error);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().getStatus()).isEqualTo(503);
        assertThat(response.getBody().getDetail()).doesNotContain("private");
    }

    static Stream<Arguments> unavailableDatabaseErrors() {
        return Stream.of(
            Arguments.of(new CannotGetJdbcConnectionException("private pool timeout")),
            Arguments.of(new DataAccessResourceFailureException("private connection failure")),
            Arguments.of(new TransientDataAccessResourceException("private temporary failure")),
            Arguments.of(new QueryTimeoutException("private query timeout")),
            Arguments.of(new RecoverableDataAccessException("private connection recovery")),
            Arguments.of(new DataIntegrityViolationException("private wrapper",
                new SQLException("private connection closed", "08006"))),
            Arguments.of(new DataIntegrityViolationException("private wrapper",
                new SQLException("private database starting", "57P03")))
        );
    }

    @Test
    void badSqlReturnsInternalServerError() {
        var response = handler.databaseFailure(new BadSqlGrammarException("private task", "private SQL",
            new SQLException("private table missing", "42P01")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().getStatus()).isEqualTo(500);
        assertThat(response.getBody().getDetail()).doesNotContain("private");
    }

    @Test
    void unrecognizedDatabaseFailureDoesNotBecomeAConflict() {
        var response = handler.databaseFailure(new DataIntegrityViolationException("private unknown failure"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().getDetail()).doesNotContain("private");
    }
}
