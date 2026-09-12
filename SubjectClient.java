import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ConnectException;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * SubjectClient - starter skeleton.
 *
 * WHAT'S ALREADY DONE FOR YOU:
 *   - Command-line argument parsing.
 *   - The interactive command loop: reading a line from standard input,
 *     splitting it into tokens, checking the argument count for each
 *     command, and printing a usage message on bad input - all without
 *     contacting the server, per the spec.
 *
 * Usage:
 *   java -jar SubjectClient.jar <server-address> <server-port>
 */
public class SubjectClient {

    private static Socket socket;
    private static BufferedReader fromServer;
    private static BufferedWriter toServer;

    public static void main(String[] args) {

        // ==================== Argument parsing (provided) ====================
        if (args.length != 2) {
            System.err.println("Usage: java -jar SubjectClient.jar <server-address> <server-port>");
            System.exit(1);
            return;
        }

        String serverAddress = args[0];
        int serverPort = -1;
        try {
            serverPort = Integer.parseInt(args[1]);
            if (serverPort < 0 || serverPort > 65535) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            System.err.println("Invalid port '" + args[1] + "': must be an integer between 0 and 65535.");
            System.exit(1);
            return;
        }

        try {
            socket = new Socket(serverAddress, serverPort);
            fromServer = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            toServer = new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        } catch (UnknownHostException e) {
            System.err.println("Unknown host '" + serverAddress + "'. Check the server address.");
            System.exit(1);
            return;
        } catch (ConnectException e) {
            System.err.println("Could not connect to " + serverAddress + ":" + serverPort
                    + " - " + e.getMessage() + ". Is the server running?");
            System.exit(1);
            return;
        } catch (IOException e) {
            System.err.println("Could not connect to " + serverAddress + ":" + serverPort
                    + " - " + e.getMessage());
            System.exit(1);
            return;
        }

        System.out.println("Connected to " + serverAddress + ":" + serverPort + ".");
        System.out.println("Type a command: query | enrol | withdraw | transfer | update | quit");

        // ==================== Interactive command loop (provided) ====================
        BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in));
        try {
            String line;
            while ((line = stdin.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;

                String[] tokens = trimmed.split("\\s+");
                String command = tokens[0].toLowerCase();

                switch (command) {
                    case "query":
                        if (tokens.length != 2) { printUsage("query <subjectCode>"); break; }
                        handleQuery(tokens[1]);
                        break;

                    case "enrol":
                        if (tokens.length != 3) { printUsage("enrol <subjectCode> <studentId>"); break; }
                        handleEnrol(tokens[1], tokens[2]);
                        break;

                    case "withdraw":
                        if (tokens.length != 3) { printUsage("withdraw <subjectCode> <studentId>"); break; }
                        handleWithdraw(tokens[1], tokens[2]);
                        break;

                    case "transfer":
                        if (tokens.length != 4) { printUsage("transfer <fromSubjectCode> <toSubjectCode> <studentId>"); break; }
                        handleTransfer(tokens[1], tokens[2], tokens[3]);
                        break;

                    case "update":
                        if (tokens.length != 3) { printUsage("update <subjectCode> <newCapacity>"); break; }
                        handleUpdateCapacity(tokens[1], tokens[2]);
                        break;

                    case "quit":
                        closeConnection();
                        System.out.println("Goodbye.");
                        return;

                    default:
                        System.out.println("Unrecognised command: " + command);
                        printUsage("query | enrol | withdraw | transfer | update | quit");
                }
            }
        } catch (IOException e) {
            System.err.println("Error reading from standard input: " + e.getMessage());
        } finally {
            closeConnection();
        }
    }

    private static void printUsage(String usage) {
        System.out.println("Usage: " + usage);
    }

    /** Closes the connection to the server. Safe to call more than once. */
    private static void closeConnection() {
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException e) {
            System.err.println("Error closing the connection: " + e.getMessage());
        }
    }

    /**
     * Sends one request as a single JSON line and returns the parsed response,
     * or null if the exchange failed - in which case the reason has already
     * been printed.
     */
    private static ProtocolMessage exchange(ProtocolMessage request) {
        try {
            toServer.write(request.toJson());
            toServer.write('\n');
            toServer.flush();

            String line = fromServer.readLine();
            if (line == null) {
                System.err.println("The server closed the connection. Type quit to exit.");
                return null;
            }
            return ProtocolMessage.parse(line);
        } catch (IOException e) {
            System.err.println("Network error talking to the server: " + e.getMessage());
            return null;
        } catch (ProtocolException e) {
            System.err.println("Unreadable response from the server: " + e.getMessage());
            return null;
        }
    }

    /** Prints the status and message of a non-SUCCESS response. */
    private static void printFailure(ProtocolMessage response) {
        String status = response.getStatus();
        String message = response.getString("message");
        System.out.println(status + (message == null ? "" : " - " + message));
    }

    /** Sends a QUERY and prints the subject's capacity, enrolled count and student IDs. */
    private static void handleQuery(String subjectCode) {
        ProtocolMessage response = exchange(ProtocolMessage.queryRequest(subjectCode));
        if (response == null) {
            return;
        }
        if (!ProtocolMessage.STATUS_SUCCESS.equals(response.getStatus())) {
            printFailure(response);
            return;
        }

        Map<String, Object> data = response.getData();
        if (data == null) {
            System.out.println("SUCCESS, but the server sent no data for " + subjectCode + ".");
            return;
        }
        System.out.println(data.get("subjectCode") + ": " + data.get("enrolledCount")
                + "/" + data.get("capacity") + " enrolled");

        Object ids = data.get("enrolledStudentIds");
        if (!(ids instanceof List) || ((List<?>) ids).isEmpty()) {
            System.out.println("  (no students enrolled)");
            return;
        }
        StringBuilder line = new StringBuilder("  ");
        List<?> list = (List<?>) ids;
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                line.append(", ");
            }
            line.append(list.get(i));
        }
        System.out.println(line);
    }

    /** Sends an ENROL and prints whether the student was enrolled. */
    private static void handleEnrol(String subjectCode, String studentId) {
        ProtocolMessage response = exchange(ProtocolMessage.enrolRequest(subjectCode, studentId));
        if (response == null) {
            return;
        }
        if (ProtocolMessage.STATUS_SUCCESS.equals(response.getStatus())) {
            System.out.println("Enrolled " + studentId + " in " + subjectCode + ".");
        } else {
            printFailure(response);
        }
    }

    /** Sends a WITHDRAW and prints whether the student was withdrawn. */
    private static void handleWithdraw(String subjectCode, String studentId) {
        ProtocolMessage response = exchange(ProtocolMessage.withdrawRequest(subjectCode, studentId));
        if (response == null) {
            return;
        }
        if (ProtocolMessage.STATUS_SUCCESS.equals(response.getStatus())) {
            System.out.println("Withdrew " + studentId + " from " + subjectCode + ".");
        } else {
            printFailure(response);
        }
    }

    /** Sends a TRANSFER and prints whether the student moved between the two subjects. */
    private static void handleTransfer(String fromSubjectCode, String toSubjectCode, String studentId) {
        ProtocolMessage response = exchange(
                ProtocolMessage.transferRequest(fromSubjectCode, toSubjectCode, studentId));
        if (response == null) {
            return;
        }
        if (ProtocolMessage.STATUS_SUCCESS.equals(response.getStatus())) {
            System.out.println("Transferred " + studentId + " from " + fromSubjectCode
                    + " to " + toSubjectCode + ".");
        } else {
            printFailure(response);
        }
    }

    /** Parses the new capacity, sends an UPDATE_CAPACITY, and prints the result. */
    private static void handleUpdateCapacity(String subjectCode, String newCapacityStr) {
        int newCapacity;
        try {
            newCapacity = Integer.parseInt(newCapacityStr);
        } catch (NumberFormatException e) {
            System.out.println("newCapacity must be an integer.");
            return;
        }
        ProtocolMessage response = exchange(
                ProtocolMessage.updateCapacityRequest(subjectCode, newCapacity));
        if (response == null) {
            return;
        }
        if (ProtocolMessage.STATUS_SUCCESS.equals(response.getStatus())) {
            System.out.println("Capacity of " + subjectCode + " set to " + newCapacity + ".");
        } else {
            printFailure(response);
        }
    }
}
