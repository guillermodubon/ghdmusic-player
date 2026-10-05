package io.github.guillermodubon.musicplayer.repository.schema;

import java.sql.SQLException;

/** A typed startup failure raised when the local database cannot be initialized safely. */
public final class DatabaseInitializationException extends SQLException {

    public enum Reason {
        UNSUPPORTED_VERSION,
        MIGRATION_FAILED
    }

    private final Reason reason;

    public DatabaseInitializationException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public DatabaseInitializationException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
