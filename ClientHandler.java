import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;

/** Handles one client connection on its own thread, reading requests and writing responses. */
public class ClientHandler implements Runnable {

    private final Socket socket;
    private final SubjectRegistry registry;
    private final SubjectServer.Sessions sessions;
    private final String name;

    private final SimpleDateFormat timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

    public ClientHandler(Socket socket, SubjectRegistry registry,
                         SubjectServer.Sessions sessions, String name) {
        this.socket = socket;
        this.registry = registry;
        this.sessions = sessions;
        this.name = name;
    }

    /** Closes this connection's socket. */
    public void closeSocket() {
        try {
            socket.close();
        } catch (IOException e) {
        }
    }

    /** Reads one request line at a time and writes back one response line, until the connection ends. */
    @Override
    public void run() {
        try {
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            BufferedWriter out = new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));

            String line;
            while ((line = in.readLine()) != null) {
                if (!sessions.beginOperation()) {
                    break;
                }
                try {
                    Reply reply = dispatch(line);
                    out.write(reply.message.toJson());
                    out.write('\n');
                    out.flush();
                    log(reply);
                } finally {
                    sessions.endOperation();
                }
            }
        } catch (SocketException e) {
            System.out.println("Connection closed: " + name);
        } catch (IOException e) {
            System.err.println("Connection error on " + name + ": " + e.getMessage());
        } finally {
            closeSocket();
            sessions.deregister(this);
        }
    }

    /** Turns one request line into one response, converting any failure into an error response. */
    private Reply dispatch(String line) {
        ProtocolMessage request;
        try {
            request = ProtocolMessage.parse(line);
        } catch (ProtocolException e) {
            return new Reply("-", "-", ProtocolMessage.errorResponse(
                    ProtocolMessage.STATUS_INVALID_REQUEST,
                    "Malformed request: " + e.getMessage()));
        }

        String op = request.getOp();
        if (op == null) {
            return new Reply("-", "-", ProtocolMessage.errorResponse(
                    ProtocolMessage.STATUS_INVALID_REQUEST,
                    "Missing or non-string \"op\" field."));
        }

        try {
            if (ProtocolMessage.OP_QUERY.equals(op)) {
                String subjectCode = request.requireString("subjectCode");
                return new Reply(op, subjectCode, registry.query(subjectCode));

            } else if (ProtocolMessage.OP_ENROL.equals(op)) {
                String subjectCode = request.requireString("subjectCode");
                String studentId = request.requireString("studentId");
                return new Reply(op, subjectCode, registry.enrol(subjectCode, studentId));

            } else if (ProtocolMessage.OP_WITHDRAW.equals(op)) {
                String subjectCode = request.requireString("subjectCode");
                String studentId = request.requireString("studentId");
                return new Reply(op, subjectCode, registry.withdraw(subjectCode, studentId));

            } else if (ProtocolMessage.OP_TRANSFER.equals(op)) {
                String fromSubjectCode = request.requireString("fromSubjectCode");
                String toSubjectCode = request.requireString("toSubjectCode");
                String studentId = request.requireString("studentId");
                return new Reply(op, fromSubjectCode + "->" + toSubjectCode,
                        registry.transfer(fromSubjectCode, toSubjectCode, studentId));

            } else if (ProtocolMessage.OP_UPDATE_CAPACITY.equals(op)) {
                String subjectCode = request.requireString("subjectCode");
                int newCapacity = request.requireInt("newCapacity");
                return new Reply(op, subjectCode, registry.updateCapacity(subjectCode, newCapacity));

            } else {
                return new Reply(op, "-", ProtocolMessage.errorResponse(
                        ProtocolMessage.STATUS_INVALID_REQUEST,
                        "Unknown operation: " + op));
            }
        } catch (ProtocolException e) {
            return new Reply(op, "-", ProtocolMessage.errorResponse(
                    ProtocolMessage.STATUS_INVALID_REQUEST, e.getMessage()));
        } catch (RuntimeException e) {
            return new Reply(op, "-", ProtocolMessage.errorResponse(
                    ProtocolMessage.STATUS_ERROR,
                    "Server error handling the request: " + e));
        }
    }

    /** Prints one operational log line for a completed operation. */
    private void log(Reply reply) {
        System.out.printf("[%s] %-10s %-16s %-24s -> %s%n",
                timestamp.format(new Date()),
                Thread.currentThread().getName(),
                reply.op,
                reply.subjectCode,
                reply.message.getStatus());
    }

    /** A response together with the operation and subject code that the log line needs. */
    private static final class Reply {
        final String op;
        final String subjectCode;
        final ProtocolMessage message;

        Reply(String op, String subjectCode, ProtocolMessage message) {
            this.op = op;
            this.subjectCode = subjectCode;
            this.message = message;
        }
    }
}
