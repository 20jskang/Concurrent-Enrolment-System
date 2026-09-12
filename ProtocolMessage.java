import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ProtocolMessage - typed helper for building and parsing the JSON request
 * and response messages defined in the assignment's Communication Format
 * and Functional Requirements sections.
 *
 * WHAT THIS CLASS DOES:
 *   - Builds correctly-shaped request messages (client side) and response
 *     messages (server side) without you having to hand-write JSON.
 *   - Parses one line of incoming JSON into a ProtocolMessage you can
 *     query with getOp() / getStatus() / getString(...) / getInt(...) / getData().
 *
 * WHAT THIS CLASS DOES NOT DO:
 *   - It does not open, read from, or write to sockets. You still write
 *     all of the socket and I/O code yourself.
 *   - It does not create or manage threads.
 *   - It does not decide *when* a status like FULL or NOT_ENROLLED applies
 *     - that is your server's business logic, using your own locking.
 *   - It does not validate business rules (e.g. "capacity must be positive").
 *     It only validates that required fields are present and are the right
 *     JSON type, and throws ProtocolException if not.
 *
 * You are free to modify or extend this class, as long as the JSON your
 * server actually sends and receives on the wire continues to match the
 * specification exactly (see Communication Format).
 *
 * ---------------------------------------------------------------------
 */
public final class ProtocolMessage {

    // ==================== Operation names ====================
    public static final String OP_QUERY = "QUERY";
    public static final String OP_ENROL = "ENROL";
    public static final String OP_WITHDRAW = "WITHDRAW";
    public static final String OP_TRANSFER = "TRANSFER";
    public static final String OP_UPDATE_CAPACITY = "UPDATE_CAPACITY";

    // ==================== Status strings ====================
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_NOT_FOUND = "NOT_FOUND";
    public static final String STATUS_FULL = "FULL";
    public static final String STATUS_DUPLICATE_ENROLMENT = "DUPLICATE_ENROLMENT";
    public static final String STATUS_NOT_ENROLLED = "NOT_ENROLLED";
    public static final String STATUS_INVALID_REQUEST = "INVALID_REQUEST";
    public static final String STATUS_ERROR = "ERROR";

    private final Map<String, Object> fields;

    private ProtocolMessage(Map<String, Object> fields) {
        this.fields = fields;
    }

    // ==================== Request builders ====================

    public static ProtocolMessage queryRequest(String subjectCode) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("op", OP_QUERY);
        m.put("subjectCode", subjectCode);
        return new ProtocolMessage(m);
    }

    public static ProtocolMessage enrolRequest(String subjectCode, String studentId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("op", OP_ENROL);
        m.put("subjectCode", subjectCode);
        m.put("studentId", studentId);
        return new ProtocolMessage(m);
    }

    public static ProtocolMessage withdrawRequest(String subjectCode, String studentId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("op", OP_WITHDRAW);
        m.put("subjectCode", subjectCode);
        m.put("studentId", studentId);
        return new ProtocolMessage(m);
    }

    public static ProtocolMessage transferRequest(String fromSubjectCode, String toSubjectCode, String studentId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("op", OP_TRANSFER);
        m.put("fromSubjectCode", fromSubjectCode);
        m.put("toSubjectCode", toSubjectCode);
        m.put("studentId", studentId);
        return new ProtocolMessage(m);
    }

    public static ProtocolMessage updateCapacityRequest(String subjectCode, int newCapacity) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("op", OP_UPDATE_CAPACITY);
        m.put("subjectCode", subjectCode);
        m.put("newCapacity", (long) newCapacity);
        return new ProtocolMessage(m);
    }

    // ==================== Response builders ====================

    /** {"status": "SUCCESS"} with no data field. */
    public static ProtocolMessage successResponse() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", STATUS_SUCCESS);
        return new ProtocolMessage(m);
    }

    /** {"status": "SUCCESS", "data": {...}} - use for QUERY responses. */
    public static ProtocolMessage successResponse(Map<String, Object> data) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", STATUS_SUCCESS);
        m.put("data", data);
        return new ProtocolMessage(m);
    }

    /**
     * Convenience builder for the "data" object of a successful QUERY response:
     * {"subjectCode": ..., "capacity": ..., "enrolledCount": ..., "enrolledStudentIds": [...]}
     */
    public static Map<String, Object> queryData(String subjectCode, int capacity,
                                                  int enrolledCount, List<String> enrolledStudentIds) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("subjectCode", subjectCode);
        data.put("capacity", (long) capacity);
        data.put("enrolledCount", (long) enrolledCount);
        data.put("enrolledStudentIds", new ArrayList<Object>(enrolledStudentIds));
        return data;
    }

    /**
     * Builder for any non-SUCCESS response: {"status": status, "message": message}
     * Use with STATUS_NOT_FOUND, STATUS_FULL, STATUS_DUPLICATE_ENROLMENT,
     * STATUS_NOT_ENROLLED, STATUS_INVALID_REQUEST, or STATUS_ERROR.
     */
    public static ProtocolMessage errorResponse(String status, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", status);
        m.put("message", message);
        return new ProtocolMessage(m);
    }

    // ==================== Parsing ====================

    /**
     * Parses one line of JSON (a single request or response object) into a
     * ProtocolMessage. Throws ProtocolException if the line is not valid
     * JSON, or is not a JSON object.
     */
    public static ProtocolMessage parse(String jsonLine) throws ProtocolException {
        if (jsonLine == null || jsonLine.trim().isEmpty()) {
            throw new ProtocolException("Empty message");
        }
        Object parsed;
        try {
            parsed = SimpleJson.decode(jsonLine.trim());
        } catch (SimpleJson.JsonParseException e) {
            throw new ProtocolException("Malformed JSON: " + e.getMessage(), e);
        }
        if (!(parsed instanceof Map)) {
            throw new ProtocolException("Expected a JSON object, got: " + jsonLine);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) parsed;
        return new ProtocolMessage(map);
    }

    // ==================== Accessors ====================

    public String getOp() {
        return getString("op");
    }

    public String getStatus() {
        return getString("status");
    }

    /** Returns the field as a String, or null if absent or not a String. */
    public String getString(String key) {
        Object v = fields.get(key);
        return (v instanceof String) ? (String) v : null;
    }

    /** Returns the field as a String, throwing ProtocolException if missing or not a string. */
    public String requireString(String key) throws ProtocolException {
        Object v = fields.get(key);
        if (!(v instanceof String) || ((String) v).isEmpty()) {
            throw new ProtocolException("Missing or invalid required field: " + key);
        }
        return (String) v;
    }

    /** Returns the field as an Integer, or null if absent or not a whole number. */
    public Integer getInt(String key) {
        Object v = fields.get(key);
        if (v instanceof Long) return (int) (long) (Long) v;
        if (v instanceof Double) {
            double d = (Double) v;
            if (d == Math.floor(d)) return (int) d;
        }
        return null;
    }

    /** Returns the field as an int, throwing ProtocolException if missing or not a whole number. */
    public int requireInt(String key) throws ProtocolException {
        Integer v = getInt(key);
        if (v == null) {
            throw new ProtocolException("Missing or invalid required field: " + key);
        }
        return v;
    }

    /** Returns the "data" object of a SUCCESS response, or null if absent. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getData() {
        Object v = fields.get("data");
        return (v instanceof Map) ? (Map<String, Object>) v : null;
    }

    /** Raw access, for any field not covered by the typed accessors above. */
    public Object getRaw(String key) {
        return fields.get(key);
    }

    // ==================== Serialization ====================

    /** Serializes this message to a single line of JSON (no trailing newline). */
    public String toJson() {
        return SimpleJson.encode(fields);
    }

    @Override
    public String toString() {
        return toJson();
    }
}
