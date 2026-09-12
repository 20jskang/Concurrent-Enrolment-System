/**
 * ProtocolException - thrown when a line of input cannot be parsed as a
 * valid protocol message, or is missing/mistypes a required field.
 *
 * This is a checked exception on purpose: the assignment specification
 * requires that a malformed request result in an INVALID_REQUEST or ERROR
 * response, without closing the connection or crashing the server. Making
 * this checked means the compiler will not let you forget to catch it and
 * turn it into that response.
 */
public class ProtocolException extends Exception {
    public ProtocolException(String message) {
        super(message);
    }

    public ProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
