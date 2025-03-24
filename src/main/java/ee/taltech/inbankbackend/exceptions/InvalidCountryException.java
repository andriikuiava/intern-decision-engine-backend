package ee.taltech.inbankbackend.exceptions;

/**
 * Thrown when the provided country is invalid.
 */
public class InvalidCountryException extends Throwable {
    private final String message;
    private final Throwable cause;

    public InvalidCountryException(String message) {
        this(message, null);
    }

    public InvalidCountryException(String message, Throwable cause) {
        this.message = message;
        this.cause = cause;
    }

    @Override
    public Throwable getCause() {
        return cause;
    }

    @Override
    public String getMessage() {
        return message;
    }
}